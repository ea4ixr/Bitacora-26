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

@SuppressWarnings({
        "unused",
        "SpellCheckingInspection",
        "CallToPrintStackTrace",
        "BusyWait",
        "FieldCanBeLocal",
        "TextBlockBlankLine",
        "HttpClientCreatorCheck",
        "SameParameterValue",
        "IfCanBeSwitch",
        "ExtractMethodRecommender",
        "RepeatedWhitespaces"
})
public class LibroGuardiaUI extends JFrame {
    /** Cluster de Bitácora 26 y ON AIR (en Cluster.java) */
    final Cluster cluster = new Cluster(this);
    /** Cuenta Bitácora 26: confirmaciones entre usuarios y QSL (en CuentaBitacora.java) */
    final CuentaBitacora cuentaB26 = new CuentaBitacora(this);
    final LogWeb logWeb = new LogWeb(this);   // LOG en bitacora26.es (copia de seguridad, en los dos sentidos)
    /** Radioclub: compartir, papeles, socios, cargos, contraseñas y ordenadores (en Radioclub.java) */
    final Radioclub radioclub = new Radioclub(this);
    /** CAT y emisoras: mis emisoras, OmniRig, pestaña Emisora, canales CB, dial y SEND (en ControlEmisora.java) */
    final ControlEmisora cat = new ControlEmisora(this);
    final PanelTrabajado panelTrabajado = new PanelTrabajado(this);   // cuadrícula de bandas y modos trabajados


    final DefaultTableModel modeloTabla;
    private final DefaultTableModel modeloPuntos;
    final JTable tablaQSOs;
    final JLabel lblEstado;
    JComboBox<String> cbSelectorActividad;
    private JButton btnGenerarDiploma;
    private JLabel lblQrzIcon;
    private JButton btnCatToggle;
    private JButton btnOperador;

    JTextField txtIndicativo;
    private JTextField txtFecha;
    private JTextField txtHora;
    /** true cuando el usuario ha escrito la hora o la fecha a mano: entonces el reloj deja de avanzar solo. */
    private boolean horaEscritaAMano = false;
    private JComboBox<String> cbHoraModo;
    private boolean horaModoLocalPrevio = false;
    static final DateTimeFormatter FMT_FECHA_UI = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final DateTimeFormatter FMT_HORA_UI = DateTimeFormatter.ofPattern("HH:mm");
    private final java.util.Map<String, javax.swing.table.TableCellEditor> editoresTabla = new java.util.HashMap<>();
    JComboBox<String> cbBanda;
    JComboBox<String> cbModo;
    JTextField txtFreqTx;
    JTextField txtFreqRx;
    JTextField txtRstSent;
    private JTextField txtRstRcvd;
    JTextField txtNombre;
    JTextField txtQth;
    JTextField txtGrid;
    private JTextField txtComentario;   // comentario del contacto (no se envía a los servicios online)

    private JPanel panelNombreContenedor;
    private CardLayout cardLayoutNombre;
    private JComboBox<String> cbNombres;
    private static final String CARD_TEXTO = "TEXTO";
    private static final String CARD_COMBO = "COMBO";
    private static final String OPCION_SELECCIONAR = "Seleccionar Operador";
    private static final String OPCION_OTRO = "Otro";

    private final Map<String, String[]> opcionesNombres = new HashMap<>();

    private static String qrzSessionKey = null;
    private static volatile String hamqthSesion = null;

    static boolean catConectado = false;
    static Thread hiloCat = null;

    static int rigSeleccionado = ConfigApp.getInt("RIG_SELECCIONADO", 1);
    @SuppressWarnings("FieldMayBeFinal")
    static String operadorActual = ConfigApp.get("OPERADOR_ACTUAL", "");

    public static final String NOMBRE_APP = "Libro de Guardia";   // nombre de la carpeta de datos: NO cambiar (ahí está la BD)
    public static final String NOMBRE_VISIBLE = "Bitácora 26";   // nombre que se ve en la aplicación
    public static final String NOMBRE_FICHERO_BD = "libro_guardia.db";

    static final String[] BANDAS = {
            "", "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "CB", "6m", "2m", "70cm"
    };

    static final String[] MODOS = {
            "", "CW", "SSB", "LSB", "USB", "AM", "FM", "FT8", "FT4"
    };

    private static final String[] BANDAS_PUNTOS = {
            "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "CB", "6m", "2m", "70cm"
    };
    private static final String[] MODOS_PUNTOS = {
            "CW", "SSB", "LSB", "USB", "AM", "FM", "FT8", "FT4"
    };

    private static final String[] COLUMNAS_ESTANDAR = {
            "Nº", "Indicativo", "Fecha", "Hora", "Banda", "Modo", "Freq TX", "Freq RX",
            "RST Sent", "RST Rcvd", "Nombre", "QTH", "Grid", "Actividad", "ID", "Operador (Indicativo)", "Modificado", "Comentario", "Confirmación"
    };

    private static final String[] COLUMNAS_PUNTOS = {
            "Nº", "Indicativo", "Nombre", "QTH", "Grid", "Actividad", "Nº Contactos", "Puntos"
    };

    static final int COL_ESTANDAR_ID = 14;

    /**
     * Selector de color: solo las pestañas Muestras (sin "Recientes"), RGB y CMYK,
     * con los nombres de las pestañas en azul oscuro (el color de fondo de la aplicación) para que se lean bien.
     */
    static Color elegirColor(Component padre, String titulo, Color inicial) {
        JColorChooser chooser = new JColorChooser(inicial == null ? Color.BLUE : inicial);
        java.util.List<javax.swing.colorchooser.AbstractColorChooserPanel> quedan = new ArrayList<>();
        for (javax.swing.colorchooser.AbstractColorChooserPanel pnl : chooser.getChooserPanels()) {
            String nombre = pnl.getDisplayName() == null ? "" : pnl.getDisplayName().toUpperCase();
            boolean muestras = pnl.getClass().getName().contains("Swatch");
            if (muestras || nombre.equals("RGB") || nombre.contains("CMYK")) quedan.add(pnl);
        }
        if (!quedan.isEmpty()) chooser.setChooserPanels(quedan.toArray(new javax.swing.colorchooser.AbstractColorChooserPanel[0]));
        // (después de colocar las pestañas, porque al colocarlas se vuelven a crear sus piezas)
        for (javax.swing.colorchooser.AbstractColorChooserPanel pnl : chooser.getChooserPanels()) {
            if (pnl.getClass().getName().contains("Swatch")) quitarRecientes(pnl);
        }
        for (Component c : todosLosComponentes(chooser)) {
            if (c instanceof JTabbedPane tp) for (int i = 0; i < tp.getTabCount(); i++) tp.setForegroundAt(i, TEMA_FONDO);
        }
        Color[] resultado = {null};
        JDialog d = JColorChooser.createDialog(padre, titulo, true, chooser, e -> resultado[0] = chooser.getColor(), null);
        d.setVisible(true);
        return resultado[0];
    }

    /** Quita el bloque "Recientes" de la pestaña de muestras del selector de color. */
    private static void quitarRecientes(JComponent panelMuestras) {
        String textoRecientes = UIManager.getString("ColorChooser.swatchesRecentText", java.util.Locale.getDefault());
        for (Component c : todosLosComponentes(panelMuestras)) {
            boolean esRecientes = c.getClass().getName().contains("RecentSwatchPanel")
                    || (c instanceof JLabel l && l.getText() != null && textoRecientes != null && l.getText().equals(textoRecientes));
            if (esRecientes) c.setVisible(false);
        }
    }

    private static java.util.List<Component> todosLosComponentes(Container c) {
        java.util.List<Component> lista = new ArrayList<>();
        for (Component x : c.getComponents()) {
            lista.add(x);
            if (x instanceof Container cc) lista.addAll(todosLosComponentes(cc));
        }
        return lista;
    }

    /** Frecuencia guardada en MHz ("7.2", "7.14701") como se ve en la tabla: "7.200.00", "7.147.01". */
    static String freqParaTabla(String mhz) {
        if (mhz == null || mhz.trim().isEmpty()) return mhz;
        try {
            long decHz = Math.round(Double.parseDouble(mhz.trim().replace(',', '.')) * 100_000);   // en decenas de Hz
            long mh = decHz / 100_000, khz = (decHz / 100) % 1000, resto = decHz % 100;
            return String.format("%d.%03d.%02d", mh, khz, resto);
        } catch (NumberFormatException e) {
            return mhz;
        }
    }

    /** Al revés: lo que se escribe en la tabla ("7.200.00", "7.2", "7200") se guarda en MHz ("7.2"). */
    static String freqDeTabla(String texto) {
        if (texto == null) return null;
        String t = texto.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.(\\d{3})\\.(\\d{1,2})").matcher(t);
        if (m.matches()) {
            String dec = (m.group(2) + (m.group(3).length() == 1 ? m.group(3) + "0" : m.group(3))).replaceAll("0+$", "");
            return dec.isEmpty() ? m.group(1) : m.group(1) + "." + dec;
        }
        return t;
    }

    /** "18/10/2026" -> "20261018" para ordenar bien por fecha. Si no tiene ese formato, se deja como está. */
    private static String claveOrdenFecha(Object valor) {
        String t = valor == null ? "" : valor.toString().trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})").matcher(t);
        if (m.matches()) {
            return m.group(3) + String.format("%02d%02d", Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        }
        return t;
    }
    // Columna extra (solo visible en el LOG Radioclub): operador que hizo el contacto e indicativo usado
    private static final int COL_ESTANDAR_OPERADOR = 15;
    // Columna "Modificado" (LOG Radioclub y actividades): indicativo de quien cambió el contacto por última vez
    private static final int COL_ESTANDAR_MODIFICADO = 16;
    // Columna "Comentario": nota libre del contacto (solo para el log; no va a los servicios online)
    private static final int COL_ESTANDAR_COMENTARIO = 17;
    // Columna "Confirmación": ★ si la otra estación (usuario de Bitácora 26) tiene el mismo contacto, y si hay QSL
    private static final int COL_ESTANDAR_CONFIRMACION = 18;

    // RADIOCLUB: el contacto se guarda una sola vez (con su operador) y, si está marcado
    // como del radioclub, aparece también en el "LOG Radioclub".
    static final String NOMBRE_VISTA_RC = "LOG Radioclub";
    private boolean bdNuevaAlArrancar = false;

    // MAPA de distancia y rumbo (a la derecha del formulario)
    private MapaQso mapaQso;
    private final java.util.concurrent.atomic.AtomicInteger secuenciaMapa = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ExecutorService hiloMapa = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Mapa");
        t.setDaemon(true);
        return t;
    });
    private javax.swing.Timer temporizadorMapa;
    private JLabel lblBandera;
    private String gridAutomatico = null;        // Grid que puso el programa a partir del QTH
    private String qthDelGridAutomatico = "";    // QTH con el que se calculó
    // El modo radioclub va con la actividad elegida (ya no se marca a mano): al arrancar, LOG Principal = no
    static boolean modoRadioclub = false;
    JCheckBox chkModoRadioclub;
    JLabel lblNombreRC, lblIndicativoActivoRC;
    JLabel lblIndicativoRC;
    JComboBox<String> cbIndicativoRC;
    // Logo del radioclub (cabecera de la ventana)
    static final int TAM_LOGO = 70;
    JLabel lblLogoRC;

    boolean mostrandoVistaPuntos = false;

    // Vista BANDA CIUDADANA: base de datos de indicativos de CB (QRZ, QRA, QTH), sin duplicados
    private static final String NOMBRE_VISTA_CB = "BANDA CIUDADANA";
    boolean mostrandoVistaCB = false;
    JLabel lblIndicativoActivoRC2;
    // Tabla y buscador (en el Directorio de Indicativos el buscador sube debajo de sus botones)
    private JScrollPane scrollTabla;
    private JPanel filaBuscar, zonaTabla, huecoBuscarCB;
    private boolean cargandoCB = false;
    private JPanel panelFormularioQso;
    private JButton btnAgregarCB;
    private JButton btnActualizarCB;
    // La 4ª columna (oculta) guarda el indicativo original de la fila, para poder renombrarlo
    // En la tabla no se escribe directamente: se usa "Editar contacto" (o doble clic), que es más seguro
    private final DefaultTableModel modeloCB = new DefaultTableModel(new Object[]{"QRZ", "QRA", "QTH", "clave"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private JButton btnEditarCB;
    private JButton btnExportarCB;
    private JButton btnImportarCB;
    private JTextField txtBuscarCB;
    private JLabel lblBuscarCB;

    // Banda/modo que exige la actividad seleccionada (vacío = sin exigencia) y aviso CAT
    private String bandaActividadReq = "";
    private String modoActividadReq = "";
    private boolean avisoCatMostrado = false;
    JLabel lblAvisoCat;

    // Bandas/modos configurados en la tabla de puntos de la actividad seleccionada (vacío = sin restricción)
    private List<String> bandasPuntosPermitidas = new ArrayList<>();
    private List<String> modosPuntosPermitidos = new ArrayList<>();

    static class InfoBanda {
        String modo;
        String freq;

        public InfoBanda(String modo, String freq) {
            this.modo = modo;
            this.freq = freq;
        }
    }

    private static class FilaEspecial {
        String indicativo;
        int puntos;

        FilaEspecial(String indicativo, int puntos) {
            this.indicativo = indicativo;
            this.puntos = puntos;
        }
    }

    private static class FilaPuntos {
        String indicativo;
        String nombre;
        String qth;
        String grid;
        String actividad;
        int numContactos;
        int puntos;
        String fechaDesempate;
        String horaDesempate;

        FilaPuntos(String indicativo, String nombre, String qth, String grid,
                   String actividad, int numContactos, int puntos,
                   String fechaDesempate, String horaDesempate) {
            this.indicativo = indicativo;
            this.nombre = nombre;
            this.qth = qth;
            this.grid = grid;
            this.actividad = actividad;
            this.numContactos = numContactos;
            this.puntos = puntos;
            this.fechaDesempate = fechaDesempate;
            this.horaDesempate = horaDesempate;
        }
    }

    static final Map<String, InfoBanda> CONFIG_BANDAS = new HashMap<>();

    static {
        CONFIG_BANDAS.put("160m", new InfoBanda("LSB", "1.840"));
        CONFIG_BANDAS.put("80m", new InfoBanda("LSB", "3.650"));
        CONFIG_BANDAS.put("60m", new InfoBanda("USB", "5.350"));
        CONFIG_BANDAS.put("40m", new InfoBanda("LSB", "7.090"));
        CONFIG_BANDAS.put("30m", new InfoBanda("CW", "10.100"));
        CONFIG_BANDAS.put("20m", new InfoBanda("USB", "14.200"));
        CONFIG_BANDAS.put("17m", new InfoBanda("USB", "18.130"));
        CONFIG_BANDAS.put("15m", new InfoBanda("USB", "21.250"));
        CONFIG_BANDAS.put("12m", new InfoBanda("USB", "24.930"));
        CONFIG_BANDAS.put("10m", new InfoBanda("USB", "28.450"));
        CONFIG_BANDAS.put("CB", new InfoBanda("USB", "27.265"));   // canal 26, USB
        CONFIG_BANDAS.put("6m", new InfoBanda("USB", "50.150"));
        CONFIG_BANDAS.put("2m", new InfoBanda("FM", "145.500"));
        CONFIG_BANDAS.put("70cm", new InfoBanda("FM", "433.500"));
    }

    // ===================== TEMA "EMISORA" (oscuro, como el panel de control) =====================
    static final Color TEMA_FONDO = new Color(26, 36, 52);
    static final Color TEMA_FONDO_2 = new Color(34, 46, 66);
    static final Color TEMA_CAMPO = new Color(12, 19, 30);
    static final Color TEMA_AMBAR = new Color(255, 190, 60);
    static final Color TEMA_TEXTO = new Color(205, 220, 245);
    static final Color TEMA_LINEA = new Color(70, 90, 120);

    /** Pone toda la aplicación con colores de "frontal de emisora". Se llama una vez, antes de crear la ventana. */
    static void aplicarTemaEmisora() {
        javax.swing.plaf.ColorUIResource fondo = new javax.swing.plaf.ColorUIResource(TEMA_FONDO);
        javax.swing.plaf.ColorUIResource fondo2 = new javax.swing.plaf.ColorUIResource(TEMA_FONDO_2);
        javax.swing.plaf.ColorUIResource campo = new javax.swing.plaf.ColorUIResource(TEMA_CAMPO);
        javax.swing.plaf.ColorUIResource ambar = new javax.swing.plaf.ColorUIResource(TEMA_AMBAR);
        javax.swing.plaf.ColorUIResource texto = new javax.swing.plaf.ColorUIResource(TEMA_TEXTO);
        javax.swing.plaf.ColorUIResource oscuro = new javax.swing.plaf.ColorUIResource(new Color(20, 28, 40));
        javax.swing.plaf.ColorUIResource linea = new javax.swing.plaf.ColorUIResource(TEMA_LINEA);
        for (String k : new String[]{"Panel.background", "OptionPane.background", "Viewport.background", "ScrollPane.background",
                "CheckBox.background", "RadioButton.background", "TabbedPane.background", "SplitPane.background",
                "MenuBar.background", "Menu.background", "PopupMenu.background", "MenuItem.background",
                "CheckBoxMenuItem.background", "RadioButtonMenuItem.background", "ToolBar.background", "Separator.background",
                "ScrollBar.background", "ScrollBar.track", "Spinner.background", "FileChooser.background", "Tree.background"})
            UIManager.put(k, fondo);
        for (String k : new String[]{"Label.foreground", "CheckBox.foreground", "RadioButton.foreground", "TitledBorder.titleColor",
                "Menu.foreground", "MenuItem.foreground", "MenuBar.foreground", "CheckBoxMenuItem.foreground",
                "RadioButtonMenuItem.foreground", "OptionPane.messageForeground", "Tree.foreground"})
            UIManager.put(k, texto);
        // Las pestañas (Muestras/RGB, QRZ/eQSL...) tienen fondo claro: su texto va en azul oscuro para que se lea
        UIManager.put("TabbedPane.foreground", fondo);
        for (String k : new String[]{"TextField", "FormattedTextField", "PasswordField", "TextArea", "TextPane", "EditorPane", "List", "ComboBox"}) {
            UIManager.put(k + ".background", campo);
            UIManager.put(k + ".foreground", ambar);
            UIManager.put(k + ".caretForeground", ambar);
            UIManager.put(k + ".selectionBackground", ambar);
            UIManager.put(k + ".selectionForeground", oscuro);
            UIManager.put(k + ".inactiveBackground", fondo2);
            UIManager.put(k + ".inactiveForeground", texto);
            UIManager.put(k + ".disabledBackground", fondo2);
        }
        UIManager.put("ComboBox.buttonBackground", fondo2);
        UIManager.put("TextField.border", BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(TEMA_LINEA), BorderFactory.createEmptyBorder(1, 3, 1, 3)));
        UIManager.put("FormattedTextField.border", UIManager.get("TextField.border"));
        UIManager.put("Table.background", campo);
        UIManager.put("Table.foreground", texto);
        UIManager.put("Table.gridColor", linea);
        UIManager.put("Table.selectionBackground", ambar);
        UIManager.put("Table.selectionForeground", oscuro);
        UIManager.put("TableHeader.background", fondo2);
        UIManager.put("TableHeader.foreground", ambar);
        UIManager.put("Menu.selectionBackground", ambar);
        UIManager.put("Menu.selectionForeground", oscuro);
        UIManager.put("MenuItem.selectionBackground", ambar);
        UIManager.put("MenuItem.selectionForeground", oscuro);
        UIManager.put("ToolTip.background", campo);
        UIManager.put("ToolTip.foreground", ambar);
        UIManager.put("TitledBorder.border", BorderFactory.createLineBorder(TEMA_LINEA));
        UIManager.put("ScrollPane.border", BorderFactory.createLineBorder(TEMA_LINEA));
        UIManager.put("MenuBar.gradient", null);
        UIManager.put("MenuBar.borderColor", linea);
        UIManager.put("ComboBox.selectionBackground", new javax.swing.plaf.ColorUIResource(new Color(60, 82, 115)));
        UIManager.put("ComboBox.selectionForeground", ambar);
        UIManager.put("RootPane.background", fondo);
        // Botones con las esquinas redondeadas y un color liso (más estilizados)
        UIManager.put("ButtonUI", BotonRedondoUI.class.getName());
        UIManager.put(BotonRedondoUI.class.getName(), BotonRedondoUI.class);
    }

    /**
     * Botón con las esquinas redondeadas y color liso. Respeta el color que se le ponga a cada botón (ámbar, rojo, verde…);
     * los que no tienen color propio salen gris claro. Los botones internos de Java (flechas de las listas, barras…) no cambian.
     */
    public static class BotonRedondoUI extends javax.swing.plaf.metal.MetalButtonUI {
        private static final int RADIO = 9;

        public static javax.swing.plaf.ComponentUI createUI(JComponent c) {
            String n = c.getClass().getName();
            boolean nuestro = c.getClass() == JButton.class || !(n.startsWith("javax.") || n.startsWith("sun.") || n.startsWith("com.sun."));
            return nuestro ? new BotonRedondoUI() : javax.swing.plaf.metal.MetalButtonUI.createUI(c);
        }

        @Override public void installDefaults(AbstractButton b) {
            super.installDefaults(b);
            LookAndFeel.installProperty(b, "opaque", Boolean.FALSE);
            b.setRolloverEnabled(true);
            if (b.getBorder() == null || b.getBorder() instanceof javax.swing.plaf.UIResource) b.setBorder(new BordeRedondo());
        }

        static boolean colorPropio(AbstractButton b) {
            return b.getBackground() != null && !(b.getBackground() instanceof javax.swing.plaf.UIResource);
        }

        private static Color mezcla(Color a, Color b, double t) {
            return new Color((int) (a.getRed() * (1 - t) + b.getRed() * t), (int) (a.getGreen() * (1 - t) + b.getGreen() * t),
                    (int) (a.getBlue() * (1 - t) + b.getBlue() * t));
        }

        @Override public void update(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            if (b.isContentAreaFilled()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean propio = colorPropio(b);
                Color base = propio ? b.getBackground() : new Color(0xE4E9F0);
                ButtonModel m = b.getModel();
                if (!b.isEnabled()) base = propio ? mezcla(base, new Color(0x6B7585), 0.55) : new Color(0xC9CFD8);
                else if (m.isPressed() && m.isArmed()) base = mezcla(base, Color.BLACK, 0.15);
                else if (m.isRollover()) base = mezcla(base, Color.WHITE, 0.18);
                g2.setColor(base);
                g2.fillRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1, RADIO, RADIO);
                g2.dispose();
            }
            paint(g, c);
        }

        @Override protected void paintButtonPressed(Graphics g, AbstractButton b) { }   // ya se oscurece en update()

        @Override protected void paintFocus(Graphics g, AbstractButton b, Rectangle viewRect, Rectangle textRect, Rectangle iconRect) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(colorPropio(b) ? new Color(255, 255, 255, 150) : new Color(0x5B7DB1));
            g2.drawRoundRect(2, 2, b.getWidth() - 5, b.getHeight() - 5, RADIO - 2, RADIO - 2);
            g2.dispose();
        }
    }

    /** Borde redondeado de los botones (con el margen de cada botón por dentro). */
    static class BordeRedondo extends javax.swing.border.AbstractBorder implements javax.swing.plaf.UIResource {
        @Override public Insets getBorderInsets(Component c, Insets i) {
            Insets m = c instanceof AbstractButton b && b.getMargin() != null ? b.getMargin() : new Insets(2, 14, 2, 14);
            i.top = m.top + 3; i.left = m.left + 3; i.bottom = m.bottom + 3; i.right = m.right + 3;
            return i;
        }
        @Override public Insets getBorderInsets(Component c) { return getBorderInsets(c, new Insets(0, 0, 0, 0)); }
        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            if (!(c instanceof AbstractButton b)) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color borde = BotonRedondoUI.colorPropio(b) ? b.getBackground().darker() : new Color(0x8E99AA);
            if (!b.isEnabled()) borde = new Color(0x7D8695);
            g2.setColor(borde);
            g2.drawRoundRect(x, y, w - 1, h - 1, BotonRedondoUI.RADIO, BotonRedondoUI.RADIO);
            g2.dispose();
        }
    }

    public LibroGuardiaUI() {
        aplicarTemaEmisora();
        getContentPane().setBackground(TEMA_FONDO);
        getRootPane().setBackground(TEMA_FONDO);
        setTitle(NOMBRE_VISIBLE + " - EA4IXR");
        // Icono de la aplicación (ventana y barra de tareas): el parche EA4IXR, en varios tamaños
        try (java.io.InputStream isIco = LibroGuardiaUI.class.getResourceAsStream("/icono_app.png")) {
            if (isIco != null) {
                BufferedImage ico = ImageIO.read(isIco);
                List<Image> tamanos = new ArrayList<>();
                for (int t : new int[]{16, 20, 24, 32, 40, 48, 64, 128, 256}) tamanos.add(ico.getScaledInstance(t, t, Image.SCALE_SMOOTH));
                setIconImages(tamanos);
            }
        } catch (Exception ignored) { }
        setSize(1350, 820);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setExtendedState(JFrame.MAXIMIZED_BOTH);   // la aplicación se abre maximizada
        setLayout(new BorderLayout(8, 8));

        // Si la base de datos todavía no existe, es un arranque "desde cero": se piden operador y radioclub
        bdNuevaAlArrancar = !new File(DatabaseManager.obtenerRutaActiva()).exists();
        DatabaseManager.inicializarBaseDatos();
        cargarOperadorInicial();
        crearMenuSuperior();

        JPanel panelEntradaQSO = crearPanelEntradaQSO();
        // En pantallas pequeñas (ordenadores antiguos) la parte de arriba no cabe: en vez de aplastarse,
        // se puede desplazar de lado con una barra. En pantallas normales se ve igual que siempre.
        add(new ControlEmisora.PanelDesplazableAncho(panelEntradaQSO), BorderLayout.NORTH);
        configurarOrdenTabulador();
        configurarEdicionCB();

        modeloTabla = new DefaultTableModel(COLUMNAS_ESTANDAR, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column != 0 && column != COL_ESTANDAR_ID && column != COL_ESTANDAR_OPERADOR && column != COL_ESTANDAR_MODIFICADO
                        && column != COL_ESTANDAR_CONFIRMACION;
            }
        };

        final Runnable recargarVista = () -> cargarQSOsDesdeBaseDatos();

        modeloPuntos = new DefaultTableModel(COLUMNAS_PUNTOS, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1 || column == 2 || column == 3 || column == 4;
            }

            @Override
            public void setValueAt(Object nuevoValor, int row, int column) {
                String indicativoActual = (String) getValueAt(row, 1);
                Object valorAnterior = getValueAt(row, column);

                super.setValueAt(nuevoValor, row, column);

                if (!mostrandoVistaPuntos) return;
                if (column < 1 || column > 4) return;

                String textoNuevo = (nuevoValor == null) ? "" : nuevoValor.toString();
                String textoAnterior = (valorAnterior == null) ? "" : valorAnterior.toString();

                if (textoNuevo.equals(textoAnterior)) return;
                if (indicativoActual == null || indicativoActual.isEmpty()) return;

                int numeralActividad = obtenerNumeralDeActividadDesdeSeleccion();
                if (numeralActividad <= 0) return;

                String[] camposBD = {null, "indicativo", "nombre", "qth", "grid"};
                String campoBD = camposBD[column];

                try (Connection conn = DatabaseManager.conectar();
                     PreparedStatement pstmt = conn.prepareStatement(
                             "UPDATE qsos SET " + campoBD + " = ? WHERE actividad_numeral = ? AND indicativo = ?")) {
                    pstmt.setString(1, textoNuevo);
                    pstmt.setInt(2, numeralActividad);
                    pstmt.setString(3, indicativoActual);
                    pstmt.executeUpdate();
                    if (campoBD.equals("indicativo")) {
                        try (PreparedStatement ps2 = conn.prepareStatement("SELECT id FROM qsos WHERE actividad_numeral = ? AND indicativo = ? LIMIT 1")) {
                            ps2.setInt(1, numeralActividad); ps2.setString(2, textoNuevo);
                            try (ResultSet rs2 = ps2.executeQuery()) { if (rs2.next()) cambiarIndicativoEnListaCB(rs2.getInt(1), indicativoActual); }
                        }
                    }
                    if (campoBD.equals("nombre") || campoBD.equals("qth")) {
                        propagarEdicionADirectorioCB("'" + indicativoActual.replace("'", "''") + "'", campoBD, textoNuevo);
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(LibroGuardiaUI.this,
                            "Error al modificar: " + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                SwingUtilities.invokeLater(recargarVista);
            }
        };

        tablaQSOs = new JTable(modeloTabla) {
            @Override
            public javax.swing.table.TableCellEditor getCellEditor(int row, int column) {
                int mc = convertColumnIndexToModel(column);
                if (getModel() == modeloTabla) {
                    switch (mc) {
                        case 2: return editorTabla("[0-9/]", false);
                        case 3: return editorTabla("[0-9:]", false);
                        case 6:
                        case 7: return editorTabla("[0-9.,]", false);
                        case 8:
                        case 9: return editorTabla("[0-9+-]", false);   // RST: números (y el signo de FT8, -08)
                        case 4: return super.getCellEditor(row, column);
                        default: return editorTabla(null, true);
                    }
                }
                return editorTabla(null, true);
            }
        };
        tablaQSOs.setFillsViewportHeight(true);
        tablaQSOs.setRowHeight(22);
        tablaQSOs.getTableHeader().setReorderingAllowed(false);

        // Al ordenar por Fecha se ordena por fecha completa (año, mes, día) y, dentro del mismo día, por hora
        final javax.swing.table.TableRowSorter<DefaultTableModel> sorterEstandar =
                new javax.swing.table.TableRowSorter<>(modeloTabla) {
                    @Override
                    public void toggleSortOrder(int column) {
                        if (column != 2) { super.toggleSortOrder(column); return; }
                        java.util.List<? extends javax.swing.RowSorter.SortKey> claves = getSortKeys();
                        javax.swing.SortOrder orden = javax.swing.SortOrder.ASCENDING;
                        if (!claves.isEmpty() && claves.get(0).getColumn() == 2
                                && claves.get(0).getSortOrder() == javax.swing.SortOrder.ASCENDING) {
                            orden = javax.swing.SortOrder.DESCENDING;
                        }
                        setSortKeys(java.util.List.of(
                                new javax.swing.RowSorter.SortKey(2, orden),
                                new javax.swing.RowSorter.SortKey(3, orden)));
                    }
                };
        final javax.swing.table.TableRowSorter<DefaultTableModel> sorterPuntos =
                new javax.swing.table.TableRowSorter<>(modeloPuntos);
        final javax.swing.table.TableRowSorter<DefaultTableModel> sorterCB =
                new javax.swing.table.TableRowSorter<>(modeloCB);

        final java.util.Comparator<Object> cmpNumerico = (a, b) -> {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;
            try {
                int na = Integer.parseInt(a.toString().trim());
                int nb = Integer.parseInt(b.toString().trim());
                return Integer.compare(na, nb);
            } catch (NumberFormatException ex) {
                return a.toString().compareTo(b.toString());
            }
        };

        // Fecha "dd/MM/yyyy": se compara como año-mes-día (antes se ordenaba por el día)
        final java.util.Comparator<Object> cmpFecha = (a, b) -> {
            String fa = claveOrdenFecha(a), fb = claveOrdenFecha(b);
            return fa.compareTo(fb);
        };

        final Runnable aplicarComparadores = () -> {
            sorterEstandar.setComparator(2, cmpFecha);
            sorterEstandar.setComparator(0, cmpNumerico);
            sorterEstandar.setComparator(COL_ESTANDAR_ID, cmpNumerico);
            sorterPuntos.setComparator(0, cmpNumerico);
            sorterPuntos.setComparator(6, cmpNumerico);
            sorterPuntos.setComparator(7, cmpNumerico);
        };
        aplicarComparadores.run();

        tablaQSOs.setRowSorter(sorterEstandar);

        tablaQSOs.addPropertyChangeListener("model", evt -> {
            if (tablaQSOs.getModel() == modeloPuntos) {
                tablaQSOs.setRowSorter(sorterPuntos);
            } else if (tablaQSOs.getModel() == modeloCB) {
                tablaQSOs.setRowSorter(sorterCB);
            } else {
                tablaQSOs.setRowSorter(sorterEstandar);
            }
            // El ordenador de filas no se entera de los cambios mientras su vista no está en pantalla:
            // se pone al día al volver (si no, la tabla se queda vacía al añadir una fila)
            if (tablaQSOs.getRowSorter() instanceof javax.swing.DefaultRowSorter<?, ?> drs) drs.allRowsChanged();
            aplicarComparadores.run();
            aplicarFiltroCB();   // la búsqueda escrita sigue valiendo al cambiar de LOG
        });

        tablaQSOs.getColumnModel().getColumn(0).setCellEditor(null);

        tablaQSOs.getColumnModel().getColumn(COL_ESTANDAR_ID).setMinWidth(0);
        tablaQSOs.getColumnModel().getColumn(COL_ESTANDAR_ID).setMaxWidth(0);
        tablaQSOs.getColumnModel().getColumn(COL_ESTANDAR_ID).setWidth(0);
        mostrarColumnaOperador(false);
        mostrarColumnaModificado(false);

        // Al seleccionar un contacto de la tabla, el mapa y la bandera muestran ese contacto
        tablaQSOs.getSelectionModel().addListSelectionListener(ev -> {
            if (!ev.getValueIsAdjusting()) mostrarFilaEnMapa();
            if (!ev.getValueIsAdjusting()) {
                String indSel = indicativoFilaSeleccionada();
                if (!indSel.isEmpty()) panelTrabajado.mostrar(indSel);
            }
        });

        // Clic izquierdo fuera del LOG (en esta ventana): se quita la selección del contacto
        Toolkit.getDefaultToolkit().addAWTEventListener(ev -> {
            if (ev.getID() != MouseEvent.MOUSE_PRESSED || !(ev instanceof MouseEvent me) || !SwingUtilities.isLeftMouseButton(me)) return;
            if (!(me.getSource() instanceof Component origen) || tablaQSOs.getSelectedRowCount() == 0) return;
            // En las zonas vacías el clic le llega a la propia ventana: se busca lo que hay de verdad debajo del ratón
            Window win = origen instanceof Window w0 ? w0 : SwingUtilities.getWindowAncestor(origen);
            if (win != this) return;              // otras ventanas y diálogos: nada
            Point pv = SwingUtilities.convertPoint(origen, me.getPoint(), getContentPane());
            Component c = SwingUtilities.getDeepestComponentAt(getContentPane(), pv.x, pv.y);
            if (c == null) c = origen;
            if (scrollTabla != null && SwingUtilities.isDescendingFrom(c, scrollTabla)) return;   // dentro del LOG
            if (c instanceof AbstractButton || c instanceof JComboBox || SwingUtilities.getAncestorOfClass(JComboBox.class, c) != null
                    || SwingUtilities.getAncestorOfClass(JPopupMenu.class, c) != null
                    || SwingUtilities.getAncestorOfClass(JMenuBar.class, c) != null) return;   // botones y menús pueden usar la selección
            if (tablaQSOs.isEditing() && !tablaQSOs.getCellEditor().stopCellEditing()) tablaQSOs.getCellEditor().cancelCellEditing();
            tablaQSOs.clearSelection();
            // que no quede marcada la casilla en la que se pinchó (el recuadro blanco)
            tablaQSOs.getSelectionModel().setAnchorSelectionIndex(-1);
            tablaQSOs.getSelectionModel().setLeadSelectionIndex(-1);
            tablaQSOs.getColumnModel().getSelectionModel().setAnchorSelectionIndex(-1);
            tablaQSOs.getColumnModel().getSelectionModel().setLeadSelectionIndex(-1);
            tablaQSOs.repaint();
        }, AWTEvent.MOUSE_EVENT_MASK);

        tablaQSOs.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (mostrandoVistaCB) {
                    if (e.getKeyCode() == KeyEvent.VK_DELETE && !tablaQSOs.isEditing()) eliminarIndicativosCB();
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_DELETE || e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                    eliminarQSOsSeleccionados();
                }
            }
        });

        tablaQSOs.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (mostrandoVistaCB && e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) editarContactoCB();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                mostrarMenuContextualSiProcede(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                mostrarMenuContextualSiProcede(e);
            }

            private void mostrarMenuContextualSiProcede(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                if (mostrandoVistaCB) {
                    int filaCB = tablaQSOs.rowAtPoint(e.getPoint());
                    if (filaCB < 0) return;
                    if (!tablaQSOs.isRowSelected(filaCB)) tablaQSOs.setRowSelectionInterval(filaCB, filaCB);
                    JPopupMenu menuCB = new JPopupMenu();
                    JMenuItem itemEliminarCB = new JMenuItem("Eliminar indicativo de la lista");
                    itemEliminarCB.addActionListener(a -> eliminarIndicativosCB());
                    menuCB.add(itemEliminarCB);
                    menuCB.show(tablaQSOs, e.getX(), e.getY());
                    return;
                }
                int fila = tablaQSOs.rowAtPoint(e.getPoint());
                if (fila < 0) return;

                // Si se pulsa sobre una fila ya seleccionada, se mantiene la selección (para actuar sobre varias)
                if (mostrandoVistaPuntos || !tablaQSOs.isRowSelected(fila)) tablaQSOs.setRowSelectionInterval(fila, fila);

                JPopupMenu menu = new JPopupMenu();

                if (mostrandoVistaPuntos) {
                    JMenuItem itemEliminar = new JMenuItem("Eliminar TODOS los QSOs de este indicativo en esta actividad");
                    itemEliminar.addActionListener(a -> eliminarQSOsSeleccionados());
                    menu.add(itemEliminar);
                } else {
                    int modelRow = tablaQSOs.convertRowIndexToModel(fila);
                    Object idObj = modeloTabla.getValueAt(modelRow, COL_ESTANDAR_ID);
                    if (idObj == null) return;

                    // Ver el indicativo en QRZ.com (no en banda ciudadana: allí no hay QRZ)
                    {
                        String callQrz = String.valueOf(modeloTabla.getValueAt(modelRow, 1)).trim().toUpperCase();
                        Object bandaObj = modeloTabla.getValueAt(modelRow, 4);
                        String bandaQrz = bandaObj == null ? "" : bandaObj.toString().trim().toUpperCase();
                        boolean esCB = bandaQrz.equals("CB") || bandaQrz.startsWith("11");
                        if (!esCB && callQrz.matches("[A-Z0-9/]{3,15}") && callQrz.matches(".*\\d.*")) {
                            JMenuItem itemQrz = new JMenuItem("Ver " + callQrz + " en QRZ.com");
                            itemQrz.addActionListener(a -> {
                                try {
                                    Desktop.getDesktop().browse(new URI("https://www.qrz.com/db/" + URLEncoder.encode(callQrz, StandardCharsets.UTF_8)));
                                } catch (Exception ex) {
                                    JOptionPane.showMessageDialog(LibroGuardiaUI.this, "No se pudo abrir el navegador: " + ex.getMessage(), "QRZ.com", JOptionPane.ERROR_MESSAGE);
                                }
                            });
                            menu.add(itemQrz);
                            menu.addSeparator();
                        }
                    }
                    JMenuItem itemEliminar = new JMenuItem("Eliminar contacto");
                    itemEliminar.addActionListener(a -> eliminarQSOsSeleccionados());
                    menu.add(itemEliminar);
                    // Subir (o volver a subir) este contacto a los servicios online marcados
                    JMenuItem itemSubir = new JMenuItem("Subir a los servicios online");
                    final int idSubir = (int) idObj;
                    itemSubir.addActionListener(a -> subirQsoPorId(idSubir));
                    menu.add(itemSubir);
                    // Confirmado por la otra estación (cuenta Bitácora 26): mandar mi QSL o ver la suya
                    Object confObj = modeloTabla.getColumnCount() > COL_ESTANDAR_CONFIRMACION ? modeloTabla.getValueAt(modelRow, COL_ESTANDAR_CONFIRMACION) : null;
                    String conf = confObj == null ? "" : confObj.toString();
                    if (!cuentaB26.cuentasB26().isEmpty()) {
                        String otro = String.valueOf(modeloTabla.getValueAt(modelRow, 1));
                        JMenuItem itemQsl = new JMenuItem(conf.contains("ENV") ? "Volver a enviar mi QSL a " + otro : "Enviar mi QSL a " + otro);
                        itemQsl.addActionListener(a -> cuentaB26.enviarQslPorId(idSubir));
                        menu.add(itemQsl);
                        if (conf.contains("REC")) {
                            JMenuItem itemVer = new JMenuItem("Ver la QSL de " + otro);
                            itemVer.addActionListener(a -> {
                                try (Connection c = DatabaseManager.conectar(); PreparedStatement ps = c.prepareStatement("SELECT uuid FROM qsos WHERE id = ?")) {
                                    ps.setInt(1, idSubir);
                                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) cuentaB26.abrirBuzonQsl(rs.getString(1)); }
                                } catch (Exception ignored) { }
                            });
                            menu.add(itemVer);
                        }
                    }
                    // Descargar mi QSL de este contacto (por ejemplo para mandarla por correo)
                    {
                        String otro = String.valueOf(modeloTabla.getValueAt(modelRow, 1));
                        JMenuItem itemBajarQsl = new JMenuItem("Descargar mi QSL de " + otro + "…");
                        itemBajarQsl.addActionListener(a -> cuentaB26.descargarQslPorId(idSubir));
                        menu.add(itemBajarQsl);
                        // En el log de una actividad con diploma: descargar el diploma de este contacto
                        int numAct = obtenerNumeralDeActividadDesdeSeleccion();
                        if (numAct > 0 && !ConfigApp.get("DIPLOMA_CFG_" + numAct, "").isEmpty()) {
                            JMenuItem itemDip = new JMenuItem("Descargar el diploma de " + otro + "…");
                            itemDip.addActionListener(a -> descargarDiplomaPorId(idSubir, numAct));
                            menu.add(itemDip);
                        }
                    }
                    // En cualquier log o actividad: pasar los contactos seleccionados a otra actividad (p. ej. si se apuntaron en otro sitio)
                    {
                        int nSel = tablaQSOs.getSelectedRowCount();
                        JMenuItem itemPasar = new JMenuItem(nSel > 1 ? "Pasar los " + nSel + " contactos seleccionados a otra actividad…" : "Pasar a otra actividad…");
                        itemPasar.addActionListener(a -> radioclub.pasarSeleccionadosAActividad());
                        menu.addSeparator();
                        menu.add(itemPasar);
                    }
                }

                menu.show(tablaQSOs, e.getX(), e.getY());
            }
        });

        modeloTabla.addTableModelListener(e -> {
            if (mostrandoVistaPuntos) return;
            int row = e.getFirstRow();
            int column = e.getColumn();

            if (row >= 0 && column == COL_ESTANDAR_COMENTARIO && modeloTabla.getColumnCount() > COL_ESTANDAR_COMENTARIO) {
                Object idObj = modeloTabla.getValueAt(row, COL_ESTANDAR_ID);
                if (idObj != null) {
                    int idQso = (int) idObj;
                    if (!radioclub.puedeTocarQso(idQso, false)) {
                        SwingUtilities.invokeLater(this::cargarQSOsDesdeBaseDatos);   // se deja como estaba
                        return;
                    }
                    Object v = modeloTabla.getValueAt(row, column);
                    actualizarQSOEnBaseDatos(idQso, "comentario", v == null ? "" : v.toString().trim());
                    actualizarQSOEnBaseDatos(idQso, "modificado_por", operadorActual == null ? "" : operadorActual);
                }
                return;
            }
            if (row >= 0 && column >= 1 && column < 14) {
                Object idObj = modeloTabla.getValueAt(row, COL_ESTANDAR_ID);
                if (idObj != null) {
                    int idQso = (int) idObj;
                    if (!radioclub.puedeTocarQso(idQso, false)) {
                        SwingUtilities.invokeLater(this::cargarQSOsDesdeBaseDatos);   // se deja como estaba
                        return;
                    }
                    // Se apunta quién lo ha cambiado (columna "Modificado")
                    actualizarQSOEnBaseDatos(idQso, "modificado_por", operadorActual == null ? "" : operadorActual);
                    if (modeloTabla.getColumnCount() > COL_ESTANDAR_MODIFICADO) {
                        final int filaMod = row;
                        SwingUtilities.invokeLater(() -> {
                            if (filaMod < modeloTabla.getRowCount()) modeloTabla.setValueAt(operadorActual, filaMod, COL_ESTANDAR_MODIFICADO);
                        });
                    }
                    if (horaLocal() && (column == 2 || column == 3)) {
                        String[] u = convertirFechaHora(String.valueOf(modeloTabla.getValueAt(row, 2)),
                                String.valueOf(modeloTabla.getValueAt(row, 3)), false);
                        actualizarQSOEnBaseDatos(idQso, "fecha", u[0]);
                        actualizarQSOEnBaseDatos(idQso, "hora", u[1]);
                        return;
                    }
                    String nuevoValor = (String) modeloTabla.getValueAt(row, column);
                    if (column == 6 || column == 7) nuevoValor = freqDeTabla(nuevoValor);   // "7.147.01" -> "7.14701"

                    String[] camposBD = {
                            "numeral", "indicativo", "fecha", "hora", "banda", "modo",
                            "freq_tx", "freq_rx", "rst_sent", "rst_rcvd",
                            "nombre", "qth", "grid", "actividad"
                    };

                    String campoBD = camposBD[column];
                    String indicativoAntes = campoBD.equals("indicativo") ? indicativoDeQso(idQso) : null;
                    actualizarQSOEnBaseDatos(idQso, campoBD, nuevoValor);
                    if (indicativoAntes != null) cambiarIndicativoEnListaCB(idQso, indicativoAntes);
                    if (campoBD.equals("nombre") || campoBD.equals("qth")) {
                        propagarEdicionADirectorioCB("(SELECT indicativo FROM qsos WHERE id = " + idQso + ")", campoBD, nuevoValor);
                    }
                }
            }
        });

        scrollTabla = new JScrollPane(tablaQSOs);
        scrollTabla.setBorder(BorderFactory.createTitledBorder("Log de QSOs (BD: " + DatabaseManager.obtenerRutaActiva() + ")"));
        // Buscador encima de la tabla: sirve para todos los LOG y actividades
        filaBuscar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        filaBuscar.setBorder(BorderFactory.createEmptyBorder(4, 4, 10, 4));
        lblBuscarCB = new JLabel("Buscar:");
        lblBuscarCB.setFont(new Font("Arial", Font.BOLD, 12));
        txtBuscarCB = new JTextField(22);
        txtBuscarCB.setToolTipText("Escribe un indicativo, nombre, QTH, banda, fecha...: la tabla muestra solo los contactos que coincidan");
        aplicarFiltroMayusculas(txtBuscarCB);
        txtBuscarCB.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { aplicarFiltroCB(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { aplicarFiltroCB(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { aplicarFiltroCB(); }
        });
        // La ✕ para borrar va dentro de la propia casilla, pequeña, y solo se ve cuando hay algo escrito
        JLabel btnLimpiarBuscar = new JLabel("✕");
        btnLimpiarBuscar.setFont(new Font("Dialog", Font.BOLD, 11));
        btnLimpiarBuscar.setForeground(new Color(143, 166, 200));
        btnLimpiarBuscar.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 5));
        btnLimpiarBuscar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnLimpiarBuscar.setToolTipText("Borrar la búsqueda");
        btnLimpiarBuscar.setPreferredSize(new Dimension(20, 10));   // siempre ocupa su sitio: la casilla no cambia de tamaño
        btnLimpiarBuscar.setText("");
        btnLimpiarBuscar.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { txtBuscarCB.setText(""); txtBuscarCB.requestFocusInWindow(); }
            @Override public void mouseEntered(MouseEvent e) { btnLimpiarBuscar.setForeground(Color.WHITE); }
            @Override public void mouseExited(MouseEvent e) { btnLimpiarBuscar.setForeground(new Color(143, 166, 200)); }
        });
        txtBuscarCB.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void ver() { btnLimpiarBuscar.setText(txtBuscarCB.getText().isEmpty() ? "" : "✕"); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { ver(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { ver(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { ver(); }
        });
        // Casilla + ✕ con el mismo fondo y borde que una casilla normal
        JPanel casillaBuscar = new JPanel(new BorderLayout());
        casillaBuscar.setBackground(txtBuscarCB.getBackground());
        casillaBuscar.setBorder(txtBuscarCB.getBorder());
        txtBuscarCB.setBorder(BorderFactory.createEmptyBorder(1, 3, 1, 0));
        casillaBuscar.add(txtBuscarCB, BorderLayout.CENTER);
        casillaBuscar.add(btnLimpiarBuscar, BorderLayout.EAST);
        filaBuscar.add(lblBuscarCB);
        filaBuscar.add(casillaBuscar);
        // A la derecha del buscador: rellenar los datos que faltan (solo al pulsarlo)
        btnActualizarContactos = new JButton("Actualizar contactos");
        btnActualizarContactos.setToolTipText("Busca y rellena el nombre, QTH o locator que falten en los contactos de este log o actividad (solo lo vacío)");
        btnActualizarContactos.setMargin(new Insets(2, 10, 2, 10));
        btnActualizarContactos.addActionListener(e -> actualizarContactosQueFaltan());
        filaBuscar.add(Box.createHorizontalStrut(14));   // separación con la casilla de buscar
        filaBuscar.add(btnActualizarContactos);
        // Ver todos los contactos, solo los de HF o solo los de CB (no borra nada: solo filtra lo que se ve)
        ButtonGroup grupoVerBanda = new ButtonGroup();
        filaBuscar.add(Box.createHorizontalStrut(24));
        for (String[] op : new String[][]{{"TODOS", "Ver todos los contactos"}, {"HF", "Ver solo los contactos de radioaficionado (todas las bandas menos CB)"},
                {"CB", "Ver solo los contactos de Banda Ciudadana"}}) {
            JToggleButton b = cat.botonSeleccion(op[0]);
            b.setFocusable(false);
            b.setMargin(new Insets(3, 18, 3, 18));
            b.setToolTipText(op[1]);
            b.setSelected(op[0].equals(verBanda));
            b.addActionListener(e -> { verBanda = op[0]; aplicarFiltroCB(); });
            grupoVerBanda.add(b);
            filaBuscar.add(b);
        }
        // Cuadrícula de bandas y modos ya trabajados con el indicativo escrito o seleccionado
        filaBuscar.add(Box.createHorizontalStrut(24));
        filaBuscar.add(panelTrabajado);
        zonaTabla = new JPanel(new BorderLayout());
        // Fila de encima de la tabla: buscador a la izquierda y, debajo del mapa, el botón CLUSTER
        cluster.filaSobreTabla = new JPanel(new BorderLayout());
        cluster.filaSobreTabla.setOpaque(false);
        cluster.filaSobreTabla.add(filaBuscar, BorderLayout.CENTER);
        cluster.btnClusterMapa = cat.botonSeleccion("CLUSTER BITÁCORA 26");
        cluster.btnClusterMapa.setFocusable(false);
        cluster.btnClusterMapa.setMargin(new Insets(3, 14, 3, 14));
        cluster.btnClusterMapa.setToolTipText("Mostrar u ocultar debajo del mapa el Cluster de Bitácora 26");
        cluster.btnClusterMapa.addActionListener(e -> {
            cluster.clusterDesplegado = cluster.btnClusterMapa.isSelected();
            cluster.actualizarClusterIncrustado();
        });
        JPanel huecoBtnCluster = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 3)) {
            @Override public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                int w = cluster.envolturaDerechaRef != null && cluster.envolturaDerechaRef.getWidth() > 0 ? cluster.envolturaDerechaRef.getWidth() + 10 : d.width;
                return new Dimension(Math.max(w, d.width), d.height);
            }
        };
        huecoBtnCluster.setOpaque(false);
        huecoBtnCluster.add(cluster.btnClusterMapa);
        cluster.filaSobreTabla.add(huecoBtnCluster, BorderLayout.EAST);
        zonaTabla.add(cluster.filaSobreTabla, BorderLayout.NORTH);
        SwingUtilities.invokeLater(cluster::actualizarClusterIncrustado);   // el botón CLUSTER solo sin cascada
        zonaTabla.add(scrollTabla, BorderLayout.CENTER);
        add(zonaTabla, BorderLayout.CENTER);

        JPanel panelInferior = new JPanel(new BorderLayout());
        panelInferior.setBorder(BorderFactory.createEmptyBorder(5, 10, 8, 10));

        lblEstado = new JLabel("Listo. Total QSOs: 0 | CAT: Desconectado");
        panelInferior.add(lblEstado, BorderLayout.WEST);
        // Resultado de la última subida a los servicios online (QRZ, eQSL...)
        lblSubida = new JLabel(" ");
        lblSubida.setFont(new Font("Dialog", Font.BOLD, 12));   // "Dialog" tiene los símbolos ✓ y ✗
        lblSubida.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lblSubida.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { verDetalleSubida(); }
        });
        lblSubida.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 12));
        panelInferior.add(lblSubida, BorderLayout.CENTER);

        JLabel lblFirma = new JLabel("created by EA4IXR Apósitos");
        lblFirma.setFont(new Font("Arial", Font.ITALIC, 11));
        lblFirma.setForeground(new Color(100, 100, 100));
        radioclub.lblSync = new JLabel(" ");
        radioclub.lblSync.setFont(new Font("Dialog", Font.BOLD, 12));
        radioclub.lblSync.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 18));
        JPanel derInferior = new JPanel(new BorderLayout());
        derInferior.setOpaque(false);
        derInferior.add(radioclub.lblSync, BorderLayout.WEST);
        radioclub.lblSyncOp = new JLabel(" ");
        radioclub.lblSyncOp.setFont(new Font("Dialog", Font.BOLD, 12));
        radioclub.lblSyncOp.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 18));
        derInferior.add(radioclub.lblSyncOp, BorderLayout.CENTER);
        derInferior.add(lblFirma, BorderLayout.EAST);
        panelInferior.add(derInferior, BorderLayout.EAST);

        add(panelInferior, BorderLayout.SOUTH);

        cargarQSOsDesdeBaseDatos();

        // Compartir con el radioclub: al arrancar y al cerrar (y cuando se quiera, con el botón "Sincronizar" del LOG Radioclub)
        // Mis ordenadores: al arrancar (y al cerrar, o con el botón "Sincronizar" del LOG Principal)
        // Al arrancar se hacen una detrás de otra (radioclub y luego mis ordenadores), con una ventanita de progreso
        javax.swing.Timer primeraSync = new javax.swing.Timer(5000, e -> radioclub.sincronizarAlArrancar());
        primeraSync.setRepeats(false);
        primeraSync.start();
        logWeb.arrancar();
        ConfigAudio.aplicarAlArrancar();   // canal de audio elegido para la entrada y la salida
        ConfirmacionesOnline.arrancar(this);   // eQSL, LoTW y QRZ.com: confirmaciones al minuto de arrancar y cada 3 horas
        // WSJT-X: los contactos que se registran en WSJT-X entran solos en el log
        arrancarEscuchaWsjtx();
        // Al arrancar: si hay una emisora en uso y activa (Configurar Emisoras), el CAT se conecta solo (un único intento)
        javax.swing.Timer catAlArrancar = new javax.swing.Timer(1500, e -> {
            ControlEmisora.EmisoraConfig enUso = cat.emisoraActiva();
            if (catConectado || enUso == null || !enUso.activa) return;
            if ("0".equals(ConfigApp.get("CAT_AUTO_ARRANQUE", "1"))) return;   // Mis emisoras: sin conectar al abrir
            conexionAutomatica = true;
            lblEstado.setText("Conectando el CAT con " + enUso.nombre + "…");
            alternarConexionCat();
        });
        catAlArrancar.setRepeats(false);
        catAlArrancar.start();
        // La primera vez que se abre el programa en este ordenador sale la ventana "Información de la aplicación"
        if (!"1".equals(ConfigApp.get("BIENVENIDA_MOSTRADA", ""))) {
            javax.swing.Timer bienvenida = new javax.swing.Timer(700, e -> {
                ConfigApp.put("BIENVENIDA_MOSTRADA", "1");
                abrirVentanaAcercaDe();
            });
            bienvenida.setRepeats(false);
            bienvenida.start();
        }
        // Actualizaciones: unos segundos después de arrancar se mira en GitHub si hay una versión nueva
        javax.swing.Timer buscarActualizacion = new javax.swing.Timer(5000, e -> comprobarActualizacionAlArrancar());
        buscarActualizacion.setRepeats(false);
        buscarActualizacion.start();
        // Recuento anónimo de programas abiertos: cada 4 minutos "sigo abierto" con un número al azar (sin indicativo ni datos)
        java.util.concurrent.ScheduledExecutorService latido = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Latido"); t.setDaemon(true); return t;
        });
        latido.scheduleWithFixedDelay(() -> {
            try {
                String id = ConfigApp.get("LATIDO_ID", "");
                if (!id.matches("[0-9a-f]{32}")) {
                    id = java.util.UUID.randomUUID().toString().replace("-", "");
                    ConfigApp.put("LATIDO_ID", id);
                }
                CuentaBitacora.postCuentas("/latido", "{\"i\":\"" + id + "\",\"p\":\"PC\"}");
            } catch (Exception ignored) { }
        }, 20, 240, java.util.concurrent.TimeUnit.SECONDS);

        // Quien ya sincronizaba sin cuenta: aviso de los días que le quedan para crearla
        javax.swing.Timer avisoSinCuenta = new javax.swing.Timer(12000, e -> cuentaB26.avisarSincronizarSinCuenta());
        avisoSinCuenta.setRepeats(false);
        avisoSinCuenta.start();
        // Ofrecer (una vez) guardar la clave del radioclub en la cuenta, para no pedirla en los otros ordenadores
        javax.swing.Timer ofrecerClaveRc = new javax.swing.Timer(20000, e -> { radioclub.revisarIndicativosAntiguosRC(); radioclub.unirseSiLaCuentaLoLleva(); });
        ofrecerClaveRc.setRepeats(false);
        ofrecerClaveRc.start();
        // Una sola vez tras actualizar: elegir qué actividades del radioclub se ven en este PC
        javax.swing.Timer preguntaActividadesRc = new javax.swing.Timer(9000, e -> radioclub.preguntarActividadesTrasActualizar());
        preguntaActividadesRc.setRepeats(false);
        preguntaActividadesRc.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                // Seguridad: si la emisora está transmitiendo por SEND, se vuelve a recepción antes de cerrar
                if (cat.txPedidoSend && catConectado) {
                    cat.pararSend(null);
                    for (int i = 0; i < 20 && !cat.ordenesCat.isEmpty(); i++) {
                        try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                    }
                }
                soltarCatAlCerrar();
                // Lo último que se haya hecho se sincroniza antes de cerrar. La ventana se quita ya (para que no parezca
                // colgada) y se espera como mucho 20 s: si internet va lento, lo que falte se sube al abrir la próxima vez.
                boolean rc = radioclub.syncActivo(), op = radioclub.syncOpActivo();
                if (!rc && !op) return;
                setVisible(false);
                Thread sync = new Thread(() -> {
                    try { if (rc) radioclub.sincronizarRadioclub(); } catch (Exception ex) { System.err.println("Sincronizar al cerrar: " + ex.getMessage()); }
                    try { if (op) radioclub.sincronizarMisOrdenadores(); } catch (Exception ex) { System.err.println("Sincronizar al cerrar: " + ex.getMessage()); }
                }, "SincronizarAlCerrar");
                sync.setDaemon(true);
                sync.start();
                try { sync.join(20_000); } catch (InterruptedException ignored) { }
            }
        });
    }

    /** Al arrancar, con varios operadores: se elige el operador y se pone su contraseña. Si se cancela, el programa se cierra. */
    private void entrarConOperador(List<String> lista, String propuesto) {
        while (true) {
            JComboBox<String> combo = new JComboBox<>(lista.toArray(new String[0]));
            combo.setSelectedItem(propuesto);
            JPasswordField pf = new JPasswordField(16);
            int r = JOptionPane.showConfirmDialog(null, new Object[]{"Operador:", combo, "Contraseña:", pf}, NOMBRE_VISIBLE + " — Entrar",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) System.exit(0);
            String op = String.valueOf(combo.getSelectedItem());
            if (!radioclub.operadorTieneClave(op)) {
                // Aún no tiene contraseña: se la pone ahora
                char[] nueva = radioclub.pedirClaveNueva(null, "Contraseña de " + op, "<html>El operador <b>" + op + "</b> todavía no tiene contraseña. Ponle una:</html>");
                if (nueva == null) continue;
                radioclub.guardarClaveOperador(op, nueva);
            } else if (!radioclub.claveOperadorCorrecta(op, pf.getPassword())) {
                JOptionPane.showMessageDialog(null, "Contraseña incorrecta.", NOMBRE_VISIBLE, JOptionPane.WARNING_MESSAGE);
                continue;
            } else if (!radioclub.cambiarTemporalSiHaceFalta(null, op)) {
                continue;
            }
            operadorActual = op;
            ConfigApp.put("OPERADOR_ACTUAL", op);
            return;
        }
    }

    private void cargarOperadorInicial() {
        String guardado = ConfigApp.get("OPERADOR_ACTUAL", "").trim().toUpperCase();

        if (!guardado.isEmpty() && !bdNuevaAlArrancar) {
            List<String> lista = obtenerListaOperadores();
            if (!lista.contains(guardado)) {
                lista.add(guardado);
                guardarListaOperadores(lista);
            }
            operadorActual = guardado;
            // Con varios operadores en este ordenador, cada uno entra con su contraseña
            if (lista.size() > 1) entrarConOperador(lista, guardado);
            return;
        }

        // Primer arranque (o sin base de datos): primero la cuenta, luego la ficha del operador
        List<String> lista = obtenerListaOperadores();
        String[] inicialesFicha = guardado.isEmpty() ? null : leerDatosOperador(guardado);
        boolean pedirClaveRC = false;
        modoInicio = null;
        claveInicioCuenta = null;
        if (guardado.isEmpty()) {
            while (true) {
                String modo = bienvenida();
                if (modo == null) {
                    JOptionPane.showMessageDialog(this, "La aplicación se cerrará porque no has elegido cómo empezar.", NOMBRE_VISIBLE, JOptionPane.WARNING_MESSAGE);
                    System.exit(0);
                }
                pedirClaveRC = false;
                if ("CLAVE".equals(modo)) {
                    // Primero la cuenta, luego la clave del radioclub
                    int r = CuentaBitacora.opcionesCuenta(this, "<html>Para unirte a tu radioclub hace falta una cuenta de Bitácora 26 (es gratuita).<br>"
                                    + "Primero entra con tu cuenta o créala; después pegarás la clave del radioclub.</html>",
                            "Clave del radioclub", JOptionPane.INFORMATION_MESSAGE, "Ya tengo cuenta", "Crear cuenta", "Volver");
                    if (r != 0 && r != 1) continue;
                    modo = r == 0 ? "TENGO" : "CREAR";
                    pedirClaveRC = true;
                }
                if ("TENGO".equals(modo)) {
                    String[] rv = cuentaB26.vincularDesdeBienvenida(this);
                    if (rv == null) continue;
                    String[] k = {"hf", "cb", "nombre", "qth", "grid"};
                    inicialesFicha = new String[5];
                    for (int i = 0; i < 5; i++) inicialesFicha[i] = campoJson(rv[0], k[i]).toUpperCase();
                    claveInicioCuenta = rv[1];
                    String cuentaId = campoJson(rv[0], "hf_id").isBlank() ? campoJson(rv[0], "cb_id") : campoJson(rv[0], "hf_id");
                    java.util.SortedMap<String, String> inv = cuentaId.isBlank() ? null : radioclub.leerClaveRCDeCuenta(cuentaId);
                    if (inv != null) invitacionInicial = inv;
                }
                modoInicio = modo;
                break;
            }
        }
        String[] datos = pedirDatosOperador(this, "Configurar operador", inicialesFicha);
        modoInicio = null;
        claveInicioCuenta = null;
        if (datos != null && pedirClaveRC && invitacionInicial == null) {
            java.util.SortedMap<String, String> inv = radioclub.pedirClaveInvitacion(this, idOperador(datos));
            if (inv != null) invitacionInicial = inv;
        }
        String nuevo = (datos == null) ? null : idOperador(datos);
        if (nuevo == null) {
            if (!guardado.isEmpty()) {
                nuevo = guardado;
            } else {
                JOptionPane.showMessageDialog(this,
                        "La aplicación se cerrará porque no has indicado un operador.",
                        "Operador obligatorio", JOptionPane.WARNING_MESSAGE);
                System.exit(0);
            }
        } else {
            guardarDatosOperador(nuevo, datos);
        }

        operadorActual = nuevo;
        ConfigApp.put("OPERADOR_ACTUAL", operadorActual);
        if (!lista.contains(nuevo)) {
            lista.add(nuevo);
            guardarListaOperadores(lista);
        }

        // Después del operador: ¿pertenece a un radioclub? (con la clave de invitación ya se sabe)
        int resp = invitacionInicial != null ? JOptionPane.OK_OPTION : JOptionPane.showConfirmDialog(this,
                (cuentaB26.hayCuenta() ? "Tu cuenta todavía no lleva ningún radioclub.\n"
                        + "(Si ya estás en un radioclub con otro ordenador, en cuanto ese ordenador sincronice con el radioclub\n"
                        + "tu cuenta lo llevará, y este ordenador se unirá solo al abrir Bitácora 26.)\n\n" : "") +
                "¿Perteneces a un radioclub y quieres rellenar sus datos ahora?\n\n" +
                        "• Aceptar: se abre la configuración del radioclub.\n" +
                        "• Cancelar: la aplicación se abre sin radioclub\n" +
                        "  (podrás configurarlo más tarde en Mi Estación → Radioclub).",
                "Radioclub", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (resp == JOptionPane.OK_OPTION) {
            radioclub.abrirDialogoConfigRadioclub();
            // Ha dicho que pertenece al radioclub: el operador queda como socio
            if (radioclub.radioclubConfigurado()) radioclub.marcarOperadorSocio(operadorActual, leerDatosOperador(operadorActual), true);
        }
    }

    // ---------- Ficha del operador: indicativo HF, indicativo CB, nombre, QTH y grid ----------

    /** El operador se identifica por su indicativo HF; si no tiene, por el de CB. */
    String idOperador(String[] datos) {
        String hf = datos[0].trim().toUpperCase();
        return hf.isEmpty() ? datos[1].trim().toUpperCase() : hf;
    }

    /** Devuelve {HF, CB, Nombre, QTH, Grid} de un operador (vacíos si no hay ficha). */
    String[] leerDatosOperador(String id) {
        String k = "OPERADOR_FICHA_" + id + "_";
        String hf = ConfigApp.get(k + "HF", "");
        String cb = ConfigApp.get(k + "CB", "");
        // Operadores antiguos sin ficha: su indicativo se toma como HF
        if (hf.isEmpty() && cb.isEmpty()) hf = id;
        return new String[]{hf, cb, ConfigApp.get(k + "NOMBRE", ""),
                ConfigApp.get(k + "QTH", ""), ConfigApp.get(k + "GRID", "")};
    }

    private void guardarDatosOperador(String id, String[] datos) {
        anadirOperadorCBAlDirectorio(datos);
        Boolean socio = operadorEsSocioPedido;
        operadorEsSocioPedido = null;
        char[] clave = operadorClavePedida;
        operadorClavePedida = null;
        if (clave != null) radioclub.guardarClaveOperador(id, clave);
        String k = "OPERADOR_FICHA_" + id + "_";
        ConfigApp.put(k + "HF", datos[0].trim().toUpperCase());
        ConfigApp.put(k + "CB", datos[1].trim().toUpperCase());
        ConfigApp.put(k + "NOMBRE", datos[2].trim().toUpperCase());
        ConfigApp.put(k + "QTH", datos[3].trim().toUpperCase());
        ConfigApp.put(k + "GRID", datos[4].trim().toUpperCase());
        if (socio != null) radioclub.marcarOperadorSocio(id, datos, socio);
    }

    /** Si el operador tiene indicativo de CB, se apunta en la lista BANDA CIUDADANA (sin borrar lo que ya hubiera). */
    void anadirOperadorCBAlDirectorio(String[] datos) {
        if (datos == null || datos.length < 4) return;
        String cb = datos[1] == null ? "" : datos[1].trim().toUpperCase();
        if (cb.isEmpty()) return;
        String nombre = datos[2] == null ? "" : datos[2].trim().toUpperCase();
        String qth = datos[3] == null ? "" : datos[3].trim().toUpperCase();
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO cb_directorio (indicativo, nombre, qth, eliminado, corte) VALUES (?, ?, ?, 0, 0) "
                             + "ON CONFLICT(indicativo) DO UPDATE SET "
                             + "nombre = CASE WHEN excluded.nombre <> '' THEN excluded.nombre ELSE nombre END, "
                             + "qth = CASE WHEN excluded.qth <> '' THEN excluded.qth ELSE qth END, eliminado = 0")) {
            ps.setString(1, cb);
            ps.setString(2, nombre);
            ps.setString(3, qth);
            ps.executeUpdate();
        } catch (Exception ex) {
            System.err.println("No se pudo añadir el indicativo CB del operador: " + ex.getMessage());
        }
    }

    /** Ventana con el mapa (calles y ciudades de OpenStreetMap) y la cuadrícula de locators. Devuelve el Grid elegido o null. */
    String elegirGridEnMapa(Component padre, String gridActual, String qth) {
        Window ventana = SwingUtilities.getWindowAncestor(padre);
        JDialog d = new JDialog(ventana, "Elegir Grid en el mapa", Dialog.ModalityType.APPLICATION_MODAL);
        MapaGrid mapa = new MapaGrid(gridActual);
        JLabel info = new JLabel("Busca tu ciudad o muévete por el mapa (rueda: acercar / alejar, arrastrar: mover) y pincha en tu QTH.");
        info.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        JLabel elegido = new JLabel(gridActual == null || gridActual.isEmpty() ? "Grid: —" : "Grid: " + gridActual.toUpperCase());
        elegido.setFont(new Font("Arial", Font.BOLD, 16));
        elegido.setForeground(TEMA_AMBAR);
        String[] resultado = {null};
        JButton usar = new JButton("Usar este Grid"), cancelar = new JButton("Cancelar");
        usar.setEnabled(false);
        mapa.alElegir = g -> { elegido.setText("Grid: " + g); usar.setEnabled(true); };
        usar.addActionListener(e -> { resultado[0] = mapa.gridElegido; d.dispose(); });
        cancelar.addActionListener(e -> d.dispose());

        // Buscador de ciudades
        JTextField txtBuscar = new JTextField(qth == null ? "" : qth, 24);
        aplicarFiltroMayusculas(txtBuscar);
        JButton btnBuscar = new JButton("Buscar");
        JLabel estadoBuscar = new JLabel(" ");
        Runnable buscar = () -> {
            String texto = txtBuscar.getText().trim();
            if (texto.isEmpty()) return;
            btnBuscar.setEnabled(false);
            estadoBuscar.setText("Buscando…");
            new Thread(() -> {
                Object[] res;   // {double[] posición, String aviso, Integer zoom} o null si no hay internet
                try { res = buscarDireccion(texto); } catch (Exception ex) { res = null; }
                final Object[] r = res;
                SwingUtilities.invokeLater(() -> {
                    btnBuscar.setEnabled(true);
                    if (r == null) { estadoBuscar.setText("Sin conexión a internet"); return; }
                    if (r[0] == null) { estadoBuscar.setText("No encontrado"); return; }
                    double[] p = (double[]) r[0];
                    estadoBuscar.setText((String) r[1]);
                    mapa.irA(p[0], p[1], (Integer) r[2]);
                    mapa.elegir(p[0], p[1]);
                });
            }, "BuscarCiudadGrid").start();
        };
        btnBuscar.addActionListener(e -> buscar.run());
        txtBuscar.addActionListener(e -> buscar.run());
        // Al dejar de escribir (1 segundo) se busca sola y el punto va a la ciudad
        javax.swing.Timer buscarAlEscribir = new javax.swing.Timer(1000, e -> buscar.run());
        buscarAlEscribir.setRepeats(false);
        txtBuscar.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { buscarAlEscribir.restart(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { buscarAlEscribir.restart(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { }
        });

        // Botón de ubicación: el Grid se pone donde está el ordenador (ubicación de Windows)
        JButton btnUbicacion = new JButton("Mi ubicación");
        btnUbicacion.setToolTipText("Pone el Grid en el sitio donde estás ahora (necesita la ubicación de Windows activada)");
        btnUbicacion.addActionListener(e -> {
            int r = JOptionPane.showConfirmDialog(d,
                    "El GRID del QTH se va a fijar en la ubicación en la que te encuentras ahora.\n\n"
                            + "Asegúrate de tener la ubicación activada en Windows:\n"
                            + "Configuración → Privacidad y seguridad → Ubicación.\n\n¿Continuar?",
                    "Mi ubicación", JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            btnUbicacion.setEnabled(false);
            estadoBuscar.setText("Buscando tu ubicación…");
            new Thread(() -> {
                double[] pos = null;
                String fallo;
                try {
                    pos = ubicacionDeWindows();
                    fallo = pos == null ? "DESCONOCIDO" : null;
                } catch (Exception ex) {
                    fallo = ex.getMessage();
                }
                final double[] p = pos;
                final String f = fallo;
                SwingUtilities.invokeLater(() -> {
                    btnUbicacion.setEnabled(true);
                    if (p != null) {
                        estadoBuscar.setText(" ");
                        mapa.irA(p[0], p[1], 14);
                        mapa.elegir(p[0], p[1]);
                        return;
                    }
                    estadoBuscar.setText("Ubicación no disponible");
                    String motivo = "DENEGADO".equals(f)
                            ? "Windows no deja a las aplicaciones usar la ubicación."
                            : "No se ha podido saber dónde estás.";
                    Object[] opciones = {"Abrir la configuración de ubicación", "Cerrar"};
                    int o = JOptionPane.showOptionDialog(d, motivo + "\n\nActiva la ubicación en Windows (Configuración → Privacidad y seguridad → Ubicación),\n"
                                    + "incluida la opción de permitir que las aplicaciones de escritorio accedan a ella, y vuelve a probar.\n"
                                    + "También puedes escribir tu ciudad en el buscador.",
                            "Mi ubicación", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opciones, opciones[0]);
                    if (o == 0) {
                        try { new ProcessBuilder("cmd", "/c", "start", "", "ms-settings:privacy-location").start(); } catch (Exception ignored) { }
                    }
                });
            }, "MiUbicacion").start();
        });

        JPanel arriba = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        arriba.add(new JLabel("Ciudad / QTH:"));
        arriba.add(txtBuscar);
        arriba.add(btnBuscar);
        arriba.add(btnUbicacion);
        arriba.add(estadoBuscar);

        JPanel abajo = new JPanel(new BorderLayout());
        abajo.add(info, BorderLayout.NORTH);
        JPanel bot = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bot.add(elegido);
        bot.add(Box.createHorizontalStrut(20));
        bot.add(usar);
        bot.add(cancelar);
        abajo.add(bot, BorderLayout.SOUTH);
        d.setLayout(new BorderLayout());
        d.add(arriba, BorderLayout.NORTH);
        d.add(mapa, BorderLayout.CENTER);
        d.add(abajo, BorderLayout.SOUTH);
        d.setSize(1000, 700);
        d.setLocationRelativeTo(ventana);
        // Sin Grid pero con QTH: se busca la ciudad nada más abrir
        if ((gridActual == null || gridALatLon(gridActual) == null) && qth != null && !qth.isBlank()) {
            SwingUtilities.invokeLater(buscar);
        }
        d.setVisible(true);
        mapa.parar();
        return resultado[0];
    }

    /**
     * Pide la posición al servicio de ubicación de Windows (con PowerShell). Devuelve {lat, lon} o null.
     * Lanza una excepción con el texto "DENEGADO" si Windows no da permiso.
     */
    /**
     * Busca una dirección (calle y número, calle, pueblo...). Si no encuentra el número, prueba sin él,
     * y si tampoco, solo con el pueblo. Devuelve {posición o null, texto para el usuario, zoom}.
     */
    static Object[] buscarDireccion(String texto) throws Exception {
        String t = texto.trim();
        double[] p = consultarNominatim(t, null);
        if (p != null) return new Object[]{p, " ", t.matches(".*\\d.*") ? 17 : 13};
        // Sin el número de la calle
        String sinNumero = t.replaceAll("(?i)\\b(N[ºO°.]?\\s*)?\\d+[A-Z]?\\b", " ").replaceAll("\\s+,", ",").replaceAll("\\s{2,}", " ").trim();
        if (!sinNumero.equals(t) && !sinNumero.isEmpty()) {
            p = consultarNominatim(sinNumero, null);
            if (p != null) return new Object[]{p, "Número no encontrado: se ha ido a la calle", 16};
        }
        // Solo el pueblo o ciudad (lo que va detrás de la última coma)
        int coma = t.lastIndexOf(',');
        if (coma > 0 && coma < t.length() - 1) {
            p = consultarNominatim(t.substring(coma + 1).trim(), null);
            if (p != null) return new Object[]{p, "Calle no encontrada: se ha ido a la ciudad", 13};
        }
        return new Object[]{null, "No encontrado", 0};
    }

    static double[] ubicacionDeWindows() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return null;
        String script = "Add-Type -AssemblyName System.Device; "
                + "$w = New-Object System.Device.Location.GeoCoordinateWatcher([System.Device.Location.GeoPositionAccuracy]::High); "
                + "[void]$w.TryStart($false, [TimeSpan]::FromSeconds(15)); "
                + "$t = 0; while (($w.Status -ne 'Ready') -and ($w.Permission -ne 'Denied') -and ($t -lt 150)) { Start-Sleep -Milliseconds 100; $t++ }; "
                + "if ($w.Permission -eq 'Denied') { 'DENEGADO' } "
                + "elseif ($w.Position.Location.IsUnknown) { 'DESCONOCIDO' } "
                + "else { $l = $w.Position.Location; [string]::Format([Globalization.CultureInfo]::InvariantCulture, 'POS;{0};{1}', $l.Latitude, $l.Longitude) }; "
                + "$w.Stop()";
        Process pr = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script)
                .redirectErrorStream(true).start();
        String salida;
        try (java.io.InputStream in = pr.getInputStream()) {
            if (!pr.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { pr.destroyForcibly(); return null; }
            salida = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String linea : salida.split("\\R")) {
            linea = linea.trim();
            if (linea.equals("DENEGADO")) throw new Exception("DENEGADO");
            if (linea.startsWith("POS;")) {
                String[] c = linea.split(";");
                return new double[]{Double.parseDouble(c[1]), Double.parseDouble(c[2])};
            }
        }
        return null;
    }

    /**
     * Mapa con las calles y ciudades de OpenStreetMap (si hay internet) y la cuadrícula de locators (Maidenhead).
     * Sin internet se ve el contorno del mundo. Al pinchar se elige el Grid de 6 caracteres.
     */
    static class MapaGrid extends JPanel {
        int zoom = 2;                 // nivel de zoom de OpenStreetMap (1..17)
        double cx, cy;                // centro, en píxeles del mundo a ese zoom
        String gridElegido;
        double[] marca;
        java.util.function.Consumer<String> alElegir;
        private Point desde, pulsado, raton;
        private volatile boolean activo = true;
        // Teselas del mapa ya descargadas (las últimas 400) y las que se están descargando
        private final Map<String, BufferedImage> teselas = new LinkedHashMap<>(256, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 400; }
        };
        private final java.util.Set<String> pidiendo = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final java.util.concurrent.ExecutorService descargas = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "TeselasMapa");
            t.setDaemon(true);
            return t;
        });
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(6)).build();

        MapaGrid(String gridActual) {
            setPreferredSize(new Dimension(1000, 560));
            MapaQso.cargarMapaSiHaceFalta();
            double[] p = gridALatLon(gridActual);
            if (p != null) {
                marca = p;
                gridElegido = gridActual.trim().toUpperCase();
                irA(p[0], p[1], 9);
            } else {
                irA(30, 0, 2);
            }
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { desde = e.getPoint(); pulsado = e.getPoint(); }
                @Override public void mouseReleased(MouseEvent e) {
                    if (pulsado != null && pulsado.distance(e.getPoint()) < 4 && SwingUtilities.isLeftMouseButton(e)) {
                        double[] ll = aLatLon(e.getX(), e.getY());
                        if (ll[0] > -85 && ll[0] < 85) elegir(ll[0], ll[1]);
                    }
                    desde = null;
                    pulsado = null;
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (desde == null) return;
                    cx -= e.getX() - desde.x;
                    cy -= e.getY() - desde.y;
                    limitar();
                    desde = e.getPoint();
                    repaint();
                }
                @Override public void mouseMoved(MouseEvent e) { raton = e.getPoint(); repaint(); }
                @Override public void mouseExited(MouseEvent e) { raton = null; repaint(); }
                @Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                    int nuevo = Math.max(1, Math.min(17, zoom + (e.getWheelRotation() < 0 ? 1 : -1)));
                    if (nuevo == zoom) return;
                    // el punto bajo el ratón se queda quieto
                    double[] ll = aLatLon(e.getX(), e.getY());
                    zoom = nuevo;
                    double[] pxy = aPixelMundo(ll[0], ll[1]);
                    cx = pxy[0] - (e.getX() - getWidth() / 2.0);
                    cy = pxy[1] - (e.getY() - getHeight() / 2.0);
                    limitar();
                    repaint();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener(m);
        }

        void parar() { activo = false; descargas.shutdownNow(); }

        void elegir(double lat, double lon) {
            marca = new double[]{lat, lon};
            gridElegido = latLonAGrid(lat, lon);
            if (alElegir != null) alElegir.accept(gridElegido);
            repaint();
        }

        void irA(double lat, double lon, int z) {
            zoom = z;
            double[] p = aPixelMundo(lat, lon);
            cx = p[0];
            cy = p[1];
            limitar();
            repaint();
        }

        private double tam() { return 256.0 * (1 << zoom); }

        private void limitar() {
            double s = tam();
            cx = ((cx % s) + s) % s;
            double mitad = getHeight() > 0 ? getHeight() / 2.0 : 280;
            cy = s <= 2 * mitad ? s / 2 : Math.max(mitad, Math.min(s - mitad, cy));
        }

        double[] aPixelMundo(double lat, double lon) {
            double s = tam();
            double la = Math.toRadians(Math.max(-85, Math.min(85, lat)));
            return new double[]{(lon + 180) / 360 * s, (1 - Math.log(Math.tan(la) + 1 / Math.cos(la)) / Math.PI) / 2 * s};
        }

        double[] aLatLon(int x, int y) {
            double s = tam();
            double px = cx + (x - getWidth() / 2.0), py = cy + (y - getHeight() / 2.0);
            double lon = px / s * 360 - 180;
            lon = ((lon + 540) % 360 + 360) % 360 - 180;
            double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * py / s))));
            return new double[]{lat, lon};
        }

        /** Posición en pantalla (x puede salir de la ventana; se elige la copia del mundo más cercana al centro). */
        private double[] aPantalla(double lat, double lon) {
            double[] p = aPixelMundo(lat, lon);
            double s = tam(), dx = p[0] - cx;
            dx = dx - Math.round(dx / s) * s;
            return new double[]{getWidth() / 2.0 + dx, getHeight() / 2.0 + (p[1] - cy)};
        }

        private BufferedImage tesela(int z, int x, int y) {
            String clave = z + "/" + x + "/" + y;
            synchronized (teselas) {
                BufferedImage img = teselas.get(clave);
                if (img != null) return img;
            }
            if (activo && pidiendo.add(clave)) {
                descargas.submit(() -> {
                    try {
                        HttpRequest req = HttpRequest.newBuilder(URI.create(urlTeselasOsm() + clave + ".png"))
                                .timeout(java.time.Duration.ofSeconds(10))
                                .header("User-Agent", AGENTE_OSM).GET().build();
                        HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                        if (r.statusCode() == 200) {
                            BufferedImage im = ImageIO.read(new java.io.ByteArrayInputStream(r.body()));
                            if (im != null) {
                                synchronized (teselas) { teselas.put(clave, im); }
                                SwingUtilities.invokeLater(this::repaint);
                            }
                        }
                    } catch (Exception ignored) {
                        // sin internet: se queda el contorno del mundo
                    } finally {
                        pidiendo.remove(clave);
                    }
                });
            }
            return null;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            int w = getWidth(), h = getHeight();
            limitar();
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(28, 74, 128));
            g.fillRect(0, 0, w, h);
            double s = tam();
            int n = 1 << zoom;
            double ox = w / 2.0 - cx, oy = h / 2.0 - cy;   // pantalla del píxel (0,0) del mundo

            // Contorno del mundo (se ve siempre, aunque no haya internet)
            if (MapaQso.tierra != null) {
                java.awt.geom.Path2D.Double tierra = new java.awt.geom.Path2D.Double(java.awt.geom.Path2D.WIND_EVEN_ODD);
                double[] c = new double[6];
                for (java.awt.geom.PathIterator it = MapaQso.tierra.getPathIterator(null); !it.isDone(); it.next()) {
                    int tipo = it.currentSegment(c);
                    if (tipo == java.awt.geom.PathIterator.SEG_CLOSE) { tierra.closePath(); continue; }
                    double[] p = aPixelMundo(-c[1], c[0]);
                    if (tipo == java.awt.geom.PathIterator.SEG_MOVETO) tierra.moveTo(p[0], p[1]); else tierra.lineTo(p[0], p[1]);
                }
                for (int copia = -1; copia <= 1; copia++) {
                    Shape sh = java.awt.geom.AffineTransform.getTranslateInstance(ox + copia * s, oy).createTransformedShape(tierra);
                    if (!sh.getBounds2D().intersects(0, 0, w, h)) continue;
                    g.setColor(new Color(118, 150, 96));
                    g.fill(sh);
                }
            }

            // Teselas de OpenStreetMap (ciudades, carreteras...)
            int tx0 = (int) Math.floor((cx - w / 2.0) / 256), tx1 = (int) Math.floor((cx + w / 2.0) / 256);
            int ty0 = Math.max(0, (int) Math.floor((cy - h / 2.0) / 256)), ty1 = Math.min(n - 1, (int) Math.floor((cy + h / 2.0) / 256));
            for (int tx = tx0; tx <= tx1; tx++) {
                for (int ty = ty0; ty <= ty1; ty++) {
                    BufferedImage im = tesela(zoom, ((tx % n) + n) % n, ty);
                    if (im != null) g.drawImage(im, (int) Math.round(ox + tx * 256.0), (int) Math.round(oy + ty * 256.0), 256, 256, null);
                }
            }

            // Cuadrícula: campos (20°x10°), cuadrados (2°x1°) y subcuadrados (5'x2,5'), según el zoom
            double[][] niveles = {{20, 10}, {2, 1}, {2.0 / 24, 1.0 / 24}};
            Color[] colores = {new Color(200, 30, 30, 220), new Color(200, 30, 30, 150), new Color(200, 30, 30, 90)};
            float[] grosor = {2f, 1.2f, 0.8f};
            double pxGrado = s / 360.0;
            double[] esq0 = aLatLon(0, h), esq1 = aLatLon(w, 0);   // abajo-izquierda, arriba-derecha
            double lonIzq = (cx - w / 2.0) / s * 360 - 180;       // sin "dar la vuelta", para dibujar seguido
            double lonDer = lonIzq + w / pxGrado;
            for (int nv = 0; nv < 3; nv++) {
                double dLon = niveles[nv][0], dLat = niveles[nv][1];
                if (dLon * pxGrado < (nv == 2 ? 20 : 10)) break;
                g.setColor(colores[nv]);
                g.setStroke(new BasicStroke(grosor[nv]));
                for (double lon = Math.floor((lonIzq + 180) / dLon) * dLon - 180; lon <= lonDer; lon += dLon) {
                    int x = (int) Math.round(w / 2.0 + (lon - (lonIzq + lonDer) / 2) * pxGrado);
                    g.drawLine(x, 0, x, h);
                }
                for (double lat = Math.floor((esq0[0] + 90) / dLat) * dLat - 90; lat <= esq1[0] + dLat; lat += dLat) {
                    if (lat < -85 || lat > 85) continue;
                    int y = (int) Math.round(aPantalla(lat, 0)[1]);
                    g.drawLine(0, y, w, y);
                }
                boolean siguienteCabe = nv < 2 && niveles[nv + 1][0] * pxGrado >= 40;
                if (!siguienteCabe && dLon * pxGrado >= 34) {
                    g.setFont(new Font("Arial", Font.BOLD, nv == 0 ? 13 : 12));
                    FontMetrics fm = g.getFontMetrics();
                    for (double lon = Math.floor((lonIzq + 180) / dLon) * dLon - 180; lon <= lonDer; lon += dLon) {
                        for (double lat = Math.floor((esq0[0] + 90) / dLat) * dLat - 90; lat <= esq1[0]; lat += dLat) {
                            if (lat < -85 || lat >= 85) continue;
                            double lonC = ((lon + dLon / 2 + 540) % 360 + 360) % 360 - 180;
                            String nombre = latLonAGrid(lat + dLat / 2, lonC).substring(0, nv == 0 ? 2 : nv == 1 ? 4 : 6);
                            int x = (int) Math.round(w / 2.0 + (lon - (lonIzq + lonDer) / 2) * pxGrado) + 4;
                            int y = (int) Math.round(aPantalla(lat + dLat, 0)[1]) + fm.getAscent() + 2;
                            int tw = fm.stringWidth(nombre);
                            g.setColor(new Color(255, 255, 255, 190));
                            g.fillRect(x - 2, y - fm.getAscent(), tw + 4, fm.getHeight());
                            g.setColor(new Color(170, 0, 0));
                            g.drawString(nombre, x, y);
                        }
                    }
                }
            }

            // Grid elegido
            if (marca != null) {
                double[] p = aPantalla(marca[0], marca[1]);
                int x = (int) Math.round(p[0]), y = (int) Math.round(p[1]);
                g.setColor(Color.RED);
                g.fillOval(x - 7, y - 7, 14, 14);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2f));
                g.drawOval(x - 7, y - 7, 14, 14);
            }
            // Grid bajo el ratón
            if (raton != null) {
                double[] ll = aLatLon(raton.x, raton.y);
                if (ll[0] > -85 && ll[0] < 85) {
                    String t = latLonAGrid(ll[0], ll[1]);
                    g.setFont(new Font("Arial", Font.BOLD, 14));
                    int tw = g.getFontMetrics().stringWidth(t);
                    g.setColor(new Color(0, 0, 0, 180));
                    g.fillRoundRect(raton.x + 12, raton.y + 8, tw + 12, 22, 8, 8);
                    g.setColor(TEMA_AMBAR);
                    g.drawString(t, raton.x + 18, raton.y + 24);
                }
            }
            // Atribución obligatoria de OpenStreetMap
            g.setFont(new Font("Arial", Font.PLAIN, 11));
            String atr = "© colaboradores de OpenStreetMap";
            int aw = g.getFontMetrics().stringWidth(atr);
            g.setColor(new Color(255, 255, 255, 200));
            g.fillRect(w - aw - 10, h - 18, aw + 10, 18);
            g.setColor(Color.DARK_GRAY);
            g.drawString(atr, w - aw - 5, h - 5);
            g.dispose();
        }
    }

    /** Ficha del operador. Devuelve {HF, CB, Nombre, QTH, Grid} o null si se cancela. */
    /** Lo que se marcó en "Es socio del radioclub" en la última ficha de operador (null si no salía). */
    private Boolean operadorEsSocioPedido;
    /** Contraseña escrita en la última ficha de operador (null = no se cambia). */
    private char[] operadorClavePedida;

    /** Cómo se empieza en el primer arranque: TENGO / CREAR (cuenta), CLAVE (del radioclub) o SIN (sin cuenta). */
    private String modoInicio;
    private String claveInicioCuenta;

    /** Bienvenida del primer arranque. Devuelve TENGO, CREAR, CLAVE, SIN o null (cerrar). */
    private String bienvenida() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        String[] eleccion = {null};
        JDialog d = new JDialog(this, "Bienvenido a " + NOMBRE_VISIBLE, Dialog.ModalityType.APPLICATION_MODAL);
        java.util.function.BiFunction<String, String, JButton> boton = (texto, modo) -> {
            JButton b = new JButton(texto);
            b.setFont(b.getFont().deriveFont(Font.BOLD, 14f));
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.addActionListener(e -> { eleccion[0] = modo; d.dispose(); });
            return b;
        };
        java.util.function.Function<String, JLabel> titulo = t -> {
            JLabel l = new JLabel(t);
            l.setFont(l.getFont().deriveFont(Font.BOLD, 15f));
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            l.setBorder(BorderFactory.createEmptyBorder(14, 0, 6, 0));
            return l;
        };
        java.util.function.Function<String, JLabel> nota = t -> {
            JLabel l = new JLabel("<html><div style='width:420px'>" + t + "</div></html>");
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            return l;
        };
        p.setBorder(BorderFactory.createEmptyBorder(10, 20, 16, 20));
        p.add(titulo.apply("1. Cuenta de Bitácora 26"));
        JPanel fila = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fila.setAlignmentX(Component.LEFT_ALIGNMENT);
        fila.add(boton.apply("Ya tengo cuenta", "TENGO"));
        fila.add(Box.createHorizontalStrut(10));
        JButton bCrearCuenta = boton.apply("Crear cuenta", "CREAR");
        CuentaBitacora.pintarCrearCuenta(bCrearCuenta);
        bCrearCuenta.setFont(bCrearCuenta.getFont().deriveFont(Font.BOLD, 14f));
        fila.add(bCrearCuenta);
        p.add(fila);
        p.add(nota.apply("Gratuita. Con ella sincronizas tus ordenadores y tu móvil, te unes a tu radioclub y recibes QSL y diplomas."));
        p.add(titulo.apply("2. Radioclub"));
        p.add(boton.apply("Tengo clave del radioclub", "CLAVE"));
        p.add(nota.apply("Si tu radioclub te ha dado una clave de invitación. Primero entrarás o crearás tu cuenta."));
        p.add(titulo.apply("3. Sin cuenta"));
        p.add(boton.apply("Usar sin cuenta", "SIN"));
        p.add(nota.apply("Puedes usar Bitácora 26 sin cuenta, pero <b>no podrás sincronizar tus ordenadores ni con el radioclub</b>: "
                + "esas opciones se verán en los menús pero estarán bloqueadas hasta que tengas cuenta."));
        d.setContentPane(p);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.pack();
        d.setLocationRelativeTo(this);
        d.setVisible(true);
        if ("SIN".equals(eleccion[0])) {
            int r = JOptionPane.showConfirmDialog(this, "<html>Vas a usar Bitácora 26 <b>sin cuenta</b>.<br><br>"
                            + "Funciona todo el LOG, pero <b>no podrás sincronizar tus ordenadores ni con el radioclub</b>.<br>"
                            + "Podrás crear la cuenta (gratis) cuando quieras en Mi Estación → Operador.<br><br>¿Seguir sin cuenta?</html>",
                    "Sin cuenta", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return bienvenida();
        }
        return eleccion[0];
    }

    private String[] pedirDatosOperador(Component padre, String titulo, String[] iniciales) {
        String[] etiquetas = {"Indicativo HF:", "Indicativo CB:", "Nombre:", "QTH:", "Grid:"};
        JTextField[] campos = new JTextField[5];
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        panel.add(new JLabel("Datos del operador (como mínimo un indicativo, HF o CB):"), gbc);
        gbc.gridwidth = 1;
        for (int i = 0; i < 5; i++) {
            campos[i] = new JTextField(iniciales == null ? "" : iniciales[i], 16);
            aplicarFiltroMayusculas(campos[i]);
            gbc.gridy = i + 1;
            gbc.gridx = 0;
            panel.add(new JLabel(etiquetas[i]), gbc);
            gbc.gridx = 1;
            panel.add(campos[i], gbc);
        }
        // Al escribir el indicativo (HF o CB), lo vacío se rellena con los datos del socio que tiene el radioclub
        for (int ci = 0; ci < 2; ci++) {
            final int c = ci;
            campos[ci].addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusLost(java.awt.event.FocusEvent e) {
                    String[] d = radioclub.datosSocioConocido(campos[c].getText());
                    if (d == null) return;
                    for (int k = 0; k < 5; k++) if (campos[k].getText().isBlank() && d[k] != null && !d[k].isBlank()) campos[k].setText(d[k].trim().toUpperCase());
                }
            });
        }
        // Botón para elegir el Grid pinchando en un mapa del mundo
        JButton btnMapaGrid = new JButton("Elegir en el mapa…");
        btnMapaGrid.setToolTipText("Abre un mapa del mundo con la cuadrícula de locators: pincha en tu QTH");
        btnMapaGrid.addActionListener(e -> {
            String elegido = elegirGridEnMapa(panel, campos[4].getText().trim(), campos[3].getText().trim());
            if (elegido != null) campos[4].setText(elegido);
        });
        gbc.gridy = 5;
        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(btnMapaGrid, gbc);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // ¿Es socio del radioclub? (solo si hay radioclub configurado)
        JCheckBox chkSocio = null;
        if (false) {   // ya no se pregunta: el radioclub da la clave de invitación y añade o quita a sus socios
            String idIni = iniciales == null ? "" : idOperador(iniciales);
            chkSocio = new JCheckBox("Es socio de " + radioclub.nombreRadioclub(),
                    !idIni.isEmpty() && "1".equals(ConfigApp.get("OPERADOR_FICHA_" + idIni + "_SOCIO", "0")));
            gbc.gridy = 6; gbc.gridx = 0; gbc.gridwidth = 3;
            panel.add(chkSocio, gbc);
            gbc.gridwidth = 1;
        }
        operadorEsSocioPedido = null;
        operadorClavePedida = null;
        // Contraseña del operador (al crearlo es obligatoria; al editarlo, vacía = no se cambia)
        boolean nuevoOperador = iniciales == null || idOperador(iniciales).isEmpty() || !radioclub.operadorTieneClave(idOperador(iniciales));
        JPasswordField pfClave = new JPasswordField(16), pfClave2 = new JPasswordField(16);
        if (claveInicioCuenta != null) { pfClave.setText(claveInicioCuenta); pfClave2.setText(claveInicioCuenta); }   // ya entró con su cuenta
        JLabel lblClave1 = new JLabel(nuevoOperador ? "Contraseña:" : "Nueva contraseña:"), lblClave2 = new JLabel("Repítela:");
        gbc.gridwidth = 1;
        gbc.gridy = 7; gbc.gridx = 0;
        panel.add(lblClave1, gbc);
        gbc.gridx = 1;
        panel.add(pfClave, gbc);
        gbc.gridy = 8; gbc.gridx = 0;
        panel.add(lblClave2, gbc);
        gbc.gridx = 1;
        panel.add(pfClave2, gbc);
        gbc.gridy = 9; gbc.gridx = 0; gbc.gridwidth = 3;
        JLabel notaClave = new JLabel(nuevoOperador ? "<html><font size='2'>Se pedirá al elegir este operador. Nadie más podrá usarlo ni cambiar sus contactos.</font></html>"
                : "<html><font size='2'>Déjala vacía para no cambiarla.</font></html>");
        panel.add(notaClave, gbc);
        gbc.gridwidth = 1;
        // Si ya tiene contraseña, los dos campos solo salen al pulsar «Cambiar contraseña»
        if (!nuevoOperador) {
            JButton btnCambiarClave = new JButton("Cambiar contraseña");
            JComponent[] deClave = {lblClave1, pfClave, lblClave2, pfClave2, notaClave};
            for (JComponent x : deClave) x.setVisible(false);
            Runnable mostrarClave = () -> {
                for (JComponent x : deClave) x.setVisible(true);
                btnCambiarClave.setVisible(false);
                Window w = SwingUtilities.getWindowAncestor(panel);
                if (w != null) w.pack();
                pfClave.requestFocusInWindow();
            };
            btnCambiarClave.addActionListener(ev -> mostrarClave.run());
            pfClave.putClientProperty("mostrar", mostrarClave);   // «Crear cuenta» la usa si hace falta escribirla
            gbc.gridy = 6; gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.WEST;
            JPanel filaCambiar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            filaCambiar.setOpaque(false);
            filaCambiar.add(btnCambiarClave);
            gbc.gridy = 10; gbc.gridx = 0; gbc.gridwidth = 3;
            gbc.insets = new Insets(8, 4, 4, 4);
            panel.add(filaCambiar, gbc);
            gbc.insets = new Insets(4, 4, 4, 4);
            gbc.gridwidth = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
        }
        // Sincronizar mis ordenadores (solo al editar un operador que ya existe)
        String idEditado = iniciales == null ? "" : idOperador(iniciales);
        if (!idEditado.isEmpty() && obtenerListaOperadores().contains(idEditado)) {
            JButton btnSyncOp = new JButton("Sincronizar mis ordenadores…");
            btnSyncOp.setToolTipText("Tener los mismos contactos y actividades en tus ordenadores (por una carpeta en la nube o FTP)");
            JLabel lblSyncOpEstado = new JLabel();
            Runnable pintarSyncOp = () -> {
                boolean activo = radioclub.syncOpActivo(idEditado);
                lblSyncOpEstado.setText(activo ? "✓ Activado" : "Desactivado");
                lblSyncOpEstado.setForeground(activo ? cat.VERDE_ENCENDIDO : new Color(150, 170, 200));
            };
            pintarSyncOp.run();
            btnSyncOp.addActionListener(ev -> { radioclub.configurarSyncMisOrdenadores(panel, idEditado); pintarSyncOp.run(); });
            JPanel filaSyncOp = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            filaSyncOp.setOpaque(false);
            filaSyncOp.add(btnSyncOp);
            filaSyncOp.add(lblSyncOpEstado);
            gbc.gridy = 11; gbc.gridx = 0; gbc.gridwidth = 3;
            gbc.insets = new Insets(12, 4, 4, 4);
            panel.add(filaSyncOp, gbc);
            gbc.insets = new Insets(4, 4, 4, 4);
            gbc.gridwidth = 1;
        }

        // Primer arranque: con la clave de invitación del radioclub se rellena todo y luego se une solo
        if ("Configurar operador".equals(titulo) && cbSelectorActividad == null && !radioclub.syncActivo() && modoInicio == null) {
            invitacionInicial = null;
            JButton btnClaveRC = new JButton("Tengo clave del radioclub…");
            JLabel lblClaveRC = new JLabel("<html><font size='2'>Si tu radioclub te ha dado una clave de invitación, pégala aquí: se rellenan tus datos<br>"
                    + "y al terminar te unes al radioclub sin más pasos.</font></html>");
            btnClaveRC.addActionListener(ev -> {
                String ya = campos[0].getText().trim().isEmpty() ? campos[1].getText().trim() : campos[0].getText().trim();
                java.util.SortedMap<String, String> inv = radioclub.pedirClaveInvitacion(panel, ya);
                if (inv == null) return;
                String[] d = {inv.getOrDefault("shf", ""), inv.getOrDefault("scb", ""), inv.getOrDefault("snom", ""), inv.getOrDefault("sqth", ""), inv.getOrDefault("sgrid", "")};
                for (int k = 0; k < 5; k++) if (campos[k].getText().isBlank() && !d[k].isBlank()) campos[k].setText(d[k].trim().toUpperCase());
                invitacionInicial = inv;
                btnClaveRC.setText("✓ Clave de " + inv.getOrDefault("n", "radioclub"));
                lblClaveRC.setText("<html><font size='2'>Pon tu contraseña y pulsa Aceptar: te unirás al radioclub al terminar.</font></html>");
            });
            JPanel filaClaveRC = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            filaClaveRC.setOpaque(false);
            filaClaveRC.add(btnClaveRC);
            filaClaveRC.add(lblClaveRC);
            gbc.gridy = 12; gbc.gridx = 0; gbc.gridwidth = 3;
            gbc.insets = new Insets(12, 4, 4, 4);
            panel.add(filaClaveRC, gbc);
            gbc.insets = new Insets(4, 4, 4, 4);
            gbc.gridwidth = 1;
        }

        // Cuenta Bitácora 26 (opcional): vincular este ordenador o crear la cuenta en la web
        gbc.gridy = 13; gbc.gridx = 0; gbc.gridwidth = 3;
        gbc.insets = new Insets(12, 4, 4, 4);
        panel.add(cuentaB26.panelCuentaB26Operador(campos, pfClave, pfClave2, notaClave, nuevoOperador
                ? "Se pedirá al elegir este operador. Nadie más podrá usarlo ni cambiar sus contactos." : "Déjala vacía para no cambiarla."), gbc);
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.gridwidth = 1;

        while (true) {
            int resp = JOptionPane.showConfirmDialog(padre, panel, titulo,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (resp != JOptionPane.OK_OPTION) { invitacionInicial = null; return null; }
            String[] datos = new String[5];
            for (int i = 0; i < 5; i++) datos[i] = campos[i].getText().trim().toUpperCase();
            if ("CREAR".equals(modoInicio) && !cuentaB26.hayCuenta()) {
                JOptionPane.showMessageDialog(padre, "Rellena tus datos y la contraseña, y pulsa «Crear cuenta» (abajo) antes de Aceptar.",
                        "Crear cuenta", JOptionPane.INFORMATION_MESSAGE);
                continue;
            }
            if (datos[0].isEmpty() && datos[1].isEmpty()) {
                JOptionPane.showMessageDialog(padre,
                        "Tienes que escribir como mínimo un indicativo (HF o CB).",
                        "Falta el indicativo", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            char[] c1 = pfClave.getPassword(), c2 = pfClave2.getPassword();
            if (nuevoOperador && c1.length == 0) {
                JOptionPane.showMessageDialog(padre, "Ponle una contraseña al operador.", "Falta la contraseña", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            if (c1.length > 0 && c1.length < 4) {
                JOptionPane.showMessageDialog(padre, "La contraseña tiene que tener como mínimo 4 caracteres.", "Contraseña", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            if (!java.util.Arrays.equals(c1, c2)) {
                JOptionPane.showMessageDialog(padre, "Las dos contraseñas no coinciden.", "Contraseña", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            // Si el operador tiene cuenta Bitácora 26, la contraseña nueva se cambia también en la cuenta (es la misma)
            String cuentaOp = idEditado.isEmpty() ? "" : cuentaB26.cuentaB26DeOperador(idEditado);
            if (c1.length > 0 && !nuevoOperador && !cuentaOp.isEmpty()) {
                if (c1.length < 8) {
                    JOptionPane.showMessageDialog(padre, "Este operador tiene cuenta Bitácora 26: la contraseña tiene que tener al menos 8 caracteres.",
                            "Contraseña", JOptionPane.WARNING_MESSAGE);
                    continue;
                }
                JPasswordField pfAnt = new JPasswordField(16);
                if (JOptionPane.showConfirmDialog(padre, new Object[]{"Para cambiarla también en tu cuenta Bitácora 26 (y en la web),",
                        "escribe la contraseña anterior:", pfAnt}, "Cambiar la contraseña", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) continue;
                String[] r = cuentaB26.firmadoB26(cuentaOp, "clave_cambiar", "{\"actual\":\"" + jsonTexto(new String(pfAnt.getPassword())) + "\",\"nueva\":\""
                        + jsonTexto(new String(c1)) + "\"}");
                if ("200".equals(r[0])) {
                    ConfigApp.put("B26_CLAVE_CAMBIO_" + cuentaOp, String.valueOf(System.currentTimeMillis() / 1000 + 5));
                    String vieja = new String(pfAnt.getPassword()), nueva = new String(c1);
                } else {
                    JOptionPane.showMessageDialog(padre, ("0".equals(r[0]) ? "Sin conexión con bitacora26.es" : cuentaB26.errorJson(r))
                            + ".\nNo se ha cambiado la contraseña (ni aquí ni en la cuenta).", "Cambiar la contraseña", JOptionPane.WARNING_MESSAGE);
                    continue;
                }
            }
            operadorClavePedida = c1.length > 0 ? c1 : null;
            operadorEsSocioPedido = chkSocio == null ? null : chkSocio.isSelected();
            // Clave del radioclub recuperada de la cuenta fuera del primer arranque: se usa ahora (en el primer arranque se usa después)
            if (invitacionInicial != null && !"Configurar operador".equals(titulo) && !radioclub.syncActivo())
                SwingUtilities.invokeLater(radioclub::abrirDialogoConfigRadioclub);
            return datos;
        }
    }

    List<String> obtenerListaOperadores() {
        String raw = ConfigApp.get("OPERADORES_GUARDADOS", "");
        List<String> lista = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return lista;
        for (String s : raw.split(",")) {
            String t = s.trim().toUpperCase();
            if (!t.isEmpty() && !lista.contains(t)) lista.add(t);
        }
        return lista;
    }

    void guardarListaOperadores(List<String> lista) {
        ConfigApp.put("OPERADORES_GUARDADOS", String.join(",", lista));
    }

    private void abrirDialogoSeleccionOperador() {
        List<String> lista = obtenerListaOperadores();

        JComboBox<String> combo = new JComboBox<>();
        combo.setEditable(false);   // aquí solo se elige: los operadores nuevos se crean en Mi Estación → Operador
        for (String op : lista) combo.addItem(op);
        combo.setSelectedItem(operadorActual);
        combo.setPreferredSize(new Dimension(220, 28));

        JDialog dialogo = new JDialog(this, "Cambiar operador", true);
        dialogo.setLayout(new BorderLayout(10, 10));
        dialogo.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JLabel lbl = new JLabel("<html>Pincha en el operador con el que vas a trabajar.<br>"
                + "<font size='2'>Para añadir operadores: Mi Estación → Operador</font></html>");
        lbl.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));

        final boolean[] aplicado = {false};

        Runnable aplicarYcerrar = () -> {
            if (aplicado[0]) return;
            Object sel = combo.getSelectedItem();
            if (sel == null) return;
            String nuevo = sel.toString().trim().toUpperCase();
            if (nuevo.isEmpty()) return;
            // Cada operador entra con su contraseña
            if (!nuevo.equals(operadorActual) && !radioclub.comprobarClaveOperador(dialogo, nuevo, "trabajar con él")) return;
            aplicado[0] = true;

            operadorActual = nuevo;
            ConfigApp.put("OPERADOR_ACTUAL", operadorActual);
            actualizarBotonOperador();
            cargarQSOsDesdeBaseDatos();
            lblEstado.setText("Operador activo: " + operadorActual);

            dialogo.dispose();
        };

        // Al elegir un operador de la lista (aunque sea el mismo) se confirma solo
        combo.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { combo.putClientProperty("cancelado", Boolean.TRUE); }
        });
        combo.addActionListener(e -> {
            if (!combo.isShowing()) return;
            if (combo.getClientProperty("cancelado") != null) { combo.putClientProperty("cancelado", null); return; }
            if ("comboBoxChanged".equals(e.getActionCommand())) SwingUtilities.invokeLater(aplicarYcerrar);
        });
        combo.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) aplicarYcerrar.run();
                else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) dialogo.dispose();
            }
        });


        JPanel panelCentro = new JPanel(new BorderLayout(8, 8));
        panelCentro.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        panelCentro.add(combo, BorderLayout.CENTER);

        dialogo.add(lbl, BorderLayout.NORTH);
        dialogo.add(panelCentro, BorderLayout.CENTER);
        dialogo.pack();
        dialogo.setLocationRelativeTo(this);
        dialogo.setVisible(true);
    }

    private void actualizarBotonOperador() {
        if (btnOperador != null) {
            btnOperador.setText("Operador: " + (operadorActual == null || operadorActual.isEmpty() ? "(sin configurar)" : operadorActual));
        }
        actualizarVisibilidadBotonDiploma();   // el botón "Sincronizar" del LOG Principal depende del operador
    }

    Runnable guardarOpcionCompartir = () -> { };
    /** Clave de invitación puesta al crear el operador en el primer arranque (para unirse al radioclub sin más pasos). */
    java.util.SortedMap<String, String> invitacionInicial;

    void guardarLogoRadioclub(boolean quitar, File elegido) {
        // Logo: se guarda una copia en PNG, así no se pierde aunque se mueva la imagen original
        if (quitar) {
            if (radioclub.ficheroLogoRadioclub().isFile() && !radioclub.ficheroLogoRadioclub().delete()) {
                System.err.println("No se pudo borrar el logo anterior");
            }
        } else if (elegido != null) {
            try {
                BufferedImage img = ImageIO.read(elegido);
                if (img == null) throw new IOException("formato de imagen no reconocido");
                ImageIO.write(img, "png", radioclub.ficheroLogoRadioclub());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "No se pudo guardar el logo:\n" + ex.getMessage(),
                        "Logo del radioclub", JOptionPane.WARNING_MESSAGE);
            }
        }
        radioclub.cargarLogoRadioclub();
    }


    // ======================= ACERCA DE =======================

    /** Carga una imagen de dentro del programa y la ajusta a la altura indicada (null si no está). */
    private static ImageIcon iconoRecurso(String recurso, int alto) {
        try (java.io.InputStream is = LibroGuardiaUI.class.getResourceAsStream(recurso)) {
            if (is == null) return null;
            BufferedImage img = ImageIO.read(is);
            if (img == null) return null;
            int ancho = Math.max(1, (int) Math.round((double) img.getWidth() * alto / img.getHeight()));
            return new ImageIcon(img.getScaledInstance(ancho, alto, Image.SCALE_SMOOTH));
        } catch (Exception e) {
            return null;
        }
    }

    /** Como iconoRecurso, pero quitando antes los bordes transparentes de la imagen. */
    private static ImageIcon iconoRecortado(String recurso, int alto) {
        try (java.io.InputStream is = LibroGuardiaUI.class.getResourceAsStream(recurso)) {
            if (is == null) return null;
            BufferedImage img = ImageIO.read(is);
            if (img == null) return null;
            int x0 = img.getWidth(), y0 = img.getHeight(), x1 = -1, y1 = -1;
            for (int y = 0; y < img.getHeight(); y++)
                for (int x = 0; x < img.getWidth(); x++)
                    if ((img.getRGB(x, y) >>> 24) > 16) { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
            if (x1 >= x0 && y1 >= y0) img = img.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
            int ancho = Math.max(1, (int) Math.round((double) img.getWidth() * alto / img.getHeight()));
            return new ImageIcon(img.getScaledInstance(ancho, alto, Image.SCALE_SMOOTH));
        } catch (Exception e) {
            return null;
        }
    }

    /** Ventana "Información de la aplicación": quién la ha creado, a qué radioclub pertenece, enlaces y logotipos. */
    private void abrirVentanaAcercaDe() {
        JDialog dialogo = new JDialog(this, "Información de la aplicación", true);
        // Sin botón "Cerrar": se cierra con la X de la ventana (o con la tecla Esc)
        dialogo.getRootPane().registerKeyboardAction(e -> dialogo.dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialogo.setContentPane(crearContenidoAcercaDe(crearBotonBuscarActualizaciones(dialogo), url -> {
            try {
                Desktop.getDesktop().browse(new URI(url));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialogo, "No se pudo abrir el navegador.\nDirección: " + url,
                        "Enlace", JOptionPane.WARNING_MESSAGE);
            }
        }));
        dialogo.pack();
        dialogo.setResizable(false);
        dialogo.setLocationRelativeTo(this);
        dialogo.setVisible(true);
    }

    /** Contenido de "Información de la aplicación": cabecera con el nombre y dos tarjetas (autor y radioclub). */
    static JPanel crearContenidoAcercaDe(JButton btnCerrar, java.util.function.Consumer<String> abrirEnlace) {
        Color azul = new Color(111, 168, 255);
        JPanel raiz = new JPanel(new BorderLayout(0, 0));
        raiz.setBackground(TEMA_FONDO);

        // ---- Cabecera: icono, nombre y para qué sirve ----
        JPanel cabecera = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
        cabecera.setBackground(TEMA_CAMPO);
        cabecera.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, TEMA_AMBAR), BorderFactory.createEmptyBorder(16, 24, 14, 24)));
        JLabel lblIcono = new JLabel();
        cabecera.add(lblIcono);
        JPanel titulos = new JPanel();
        titulos.setOpaque(false);
        titulos.setLayout(new BoxLayout(titulos, BoxLayout.Y_AXIS));
        JLabel nombre = new JLabel(NOMBRE_VISIBLE);
        nombre.setFont(new Font("Arial", Font.BOLD, 26));
        nombre.setForeground(TEMA_AMBAR);
        JLabel subtitulo = new JLabel("Libro de guardia para radioaficionados, Banda Ciudadana y radioclubs");
        subtitulo.setFont(new Font("Arial", Font.PLAIN, 13));
        subtitulo.setForeground(TEMA_TEXTO);
        titulos.add(nombre);
        titulos.add(Box.createVerticalStrut(4));
        titulos.add(subtitulo);
        JLabel licencia = new JLabel("Software libre (licencia GPL v3) · FT8 y FT4 basados en WSJT-X, de K1JT y colaboradores");
        licencia.setFont(new Font("Arial", Font.PLAIN, 11));
        licencia.setForeground(new Color(143, 166, 200));
        titulos.add(Box.createVerticalStrut(3));
        titulos.add(licencia);
        cabecera.add(titulos);
        // El icono, tan alto como el nombre y la frase de debajo (sin los bordes transparentes de la imagen)
        int altoIcono = nombre.getPreferredSize().height + 4 + subtitulo.getPreferredSize().height;
        ImageIcon icono = iconoRecortado("/icono_app.png", altoIcono);
        if (icono != null) lblIcono.setIcon(icono);
        // Toda la cabecera es un botón: abre la web de Bitácora 26
        cabecera.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cabecera.setToolTipText("Abrir la web de Bitácora 26 (bitacora26.es)");
        cabecera.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { abrirEnlace.accept("https://bitacora26.es"); }
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { cabecera.setBackground(TEMA_CAMPO.brighter()); }
            @Override public void mouseExited(java.awt.event.MouseEvent e) { cabecera.setBackground(TEMA_CAMPO); }
        });
        raiz.add(cabecera, BorderLayout.NORTH);

        // ---- Dos tarjetas: quién la ha creado y su radioclub ----
        String webClub = ConfigApp.get("RADIOCLUB_WEB", "").trim();
        if (webClub.isEmpty()) webClub = "https://30cs26.wixsite.com/my-site";
        else if (!webClub.toLowerCase().startsWith("http")) webClub = "https://" + webClub;
        JPanel tarjetas = new JPanel(new GridLayout(1, 2, 18, 0));
        tarjetas.setOpaque(false);
        tarjetas.add(tarjetaAcercaDe("CREADA POR", "/logo_ea4ixr.png", "Javier Alguacil",
                "EA4IXR", "https://www.qrz.com/db/EA4IXR", "Ver EA4IXR en QRZ.com", azul, abrirEnlace));
        tarjetas.add(tarjetaAcercaDe("SOCIO DEL RADIOCLUB", "/logo_charlie_sierra_26.png", "Charlie Sierra 26",
                "EA4RCS  ·  30CS26", webClub, "Abrir la web del radioclub", azul, abrirEnlace));

        // ---- Texto de presentación ----
        JLabel texto = new JLabel("<html><div style='text-align:center; width:430px'>"
                + "<b>" + NOMBRE_VISIBLE + "</b> es un proyecto que nace de la afición, para llevar el registro de contactos "
                + "de la forma más sencilla, tanto en las bandas de radioaficionado como en la banda de CB. "
                + "Además, permite gestionar el radioclub, las actividades, los logs y un directorio de indicativos de CB."
                + "</div></html>");
        texto.setFont(new Font("Arial", Font.PLAIN, 13));
        texto.setForeground(TEMA_TEXTO);
        texto.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel centro = new JPanel(new BorderLayout(0, 18));
        centro.setOpaque(false);
        centro.setBorder(BorderFactory.createEmptyBorder(20, 24, 6, 24));
        centro.add(tarjetas, BorderLayout.CENTER);
        centro.add(texto, BorderLayout.SOUTH);
        raiz.add(centro, BorderLayout.CENTER);

        // ---- Pie: firma y botón Cerrar ----
        JPanel pie = new JPanel(new BorderLayout());
        pie.setOpaque(false);
        pie.setBorder(BorderFactory.createEmptyBorder(8, 24, 14, 24));
        String version = versionInstalada();
        JLabel firma = new JLabel("© " + java.time.Year.now() + " EA4IXR · " + (version.isEmpty() ? "" : "Versión " + version + " · ") + "73 y buenos DX");
        firma.setFont(new Font("Arial", Font.ITALIC, 11));
        firma.setForeground(new Color(130, 145, 170));
        pie.add(firma, BorderLayout.WEST);
        if (btnCerrar != null) pie.add(btnCerrar, BorderLayout.EAST);
        // ---- Mensaje (sin donativos: Bitácora 26 es gratuita y no pide dinero) ----
        JPanel donar = new JPanel(new BorderLayout(14, 0));
        donar.setBackground(TEMA_FONDO_2);
        donar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, TEMA_LINEA),
                BorderFactory.createEmptyBorder(10, 24, 10, 24)));
        JLabel txtDonar = new JLabel("<html><div style='width:480px'>" + NOMBRE_VISIBLE + " es gratuita y se "
                + "comparte con todos los colegas. ¡73 y buenos DX!</div></html>");
        txtDonar.setFont(new Font("Arial", Font.PLAIN, 12));
        txtDonar.setForeground(TEMA_TEXTO);
        donar.add(txtDonar, BorderLayout.CENTER);
        JPanel abajo = new JPanel(new BorderLayout());
        abajo.setOpaque(false);
        abajo.add(donar, BorderLayout.NORTH);
        abajo.add(pie, BorderLayout.SOUTH);
        raiz.add(abajo, BorderLayout.SOUTH);
        return raiz;
    }

    // ===================== ACTUALIZACIONES AUTOMÁTICAS (desde GitHub) =====================
    // Al arrancar, el programa mira en GitHub (sección "Releases") si hay una versión más nueva.
    // Si la hay, pregunta; si se acepta, descarga "Bitacora26-actualizacion.zip", lo comprueba,
    // se cierra, un pequeño ayudante (PowerShell) cambia los archivos y vuelve a abrir el programa.
    // Los datos (contactos y configuración) están en Documentos y no se tocan.

    /** Última versión publicada en GitHub. */
    static final String URL_ULTIMA_VERSION = "https://api.github.com/repos/ea4ixr/Bitacora-26/releases/latest";
    /** Nombre del archivo de actualización que se sube en cada versión de GitHub. */
    static final String ARCHIVO_ACTUALIZACION = "Bitacora26-actualizacion.zip";

    /** Datos de una versión nueva encontrada en GitHub. */
    record VersionNueva(String version, String url, String sha256, long tamano, String notas) { }

    /** Versión del programa. Tiene que ser la misma que APP_VERSION de empaquetar.bat. */
    static final String VERSION_APP = "1.39";

    /** Versión del programa instalado (la pone empaquetar.bat; desde IntelliJ, la de VERSION_APP). */
    static String versionInstalada() {
        String v = System.getProperty("jpackage.app-version", "").trim();
        return v.isEmpty() ? VERSION_APP : v;
    }

    /** Carpeta donde está "Bitacora 26.exe", o null si no se ejecuta desde el EXE. */
    static File carpetaPrograma() {
        String exe = System.getProperty("jpackage.app-path");
        if (exe == null || exe.isBlank()) return null;
        File f = new File(exe).getAbsoluteFile().getParentFile();
        return f != null && f.isDirectory() ? f : null;
    }

    /** Carpeta temporal donde se prepara la actualización. */
    static File carpetaTemporalActualizacion() {
        return new File(System.getProperty("java.io.tmpdir"), "Bitacora26_actualizacion");
    }

    /** Compara dos versiones tipo "1.2" o "v1.10.3". Devuelve >0 si a es más nueva que b. */
    static int compararVersiones(String a, String b) {
        String[] pa = a.replaceAll("^[^0-9]+", "").split("[^0-9]+");
        String[] pb = b.replaceAll("^[^0-9]+", "").split("[^0-9]+");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = 0, y = 0;
            try { if (i < pa.length && !pa[i].isEmpty()) x = Long.parseLong(pa[i]); } catch (NumberFormatException ignored) { }
            try { if (i < pb.length && !pb[i].isEmpty()) y = Long.parseLong(pb[i]); } catch (NumberFormatException ignored) { }
            if (x != y) return Long.compare(x, y);
        }
        return 0;
    }

    /** Lee el texto JSON que empieza en la comilla de la posición indicada. */
    private static String leerTextoJson(String json, int comilla) {
        StringBuilder sb = new StringBuilder();
        for (int i = comilla + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> { }
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            try { sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16)); } catch (NumberFormatException ignored) { }
                            i += 4;
                        }
                    }
                    default -> sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }

    /** Valor de texto de un campo JSON a partir de una posición (o null). */
    private static String campoTextoJson(String json, String campo, int desde) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + campo + "\"\\s*:\\s*\"").matcher(json);
        if (!m.find(Math.max(0, desde))) return null;
        return leerTextoJson(json, m.end() - 1);
    }

    /** Valor numérico de un campo JSON a partir de una posición (o -1). */
    private static long campoNumeroJson(String json, String campo, int desde) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + campo + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find(Math.max(0, desde))) return -1;
        try { return Long.parseLong(m.group(1)); } catch (NumberFormatException e) { return -1; }
    }

    /** Pregunta a GitHub por la última versión publicada. Devuelve null si todavía no hay ninguna publicada.
     *  Si esa versión no trae archivo de actualización, url queda vacía. Lanza una excepción si no hay conexión. */
    static VersionNueva consultarUltimaVersion() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(URL_ULTIMA_VERSION))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("User-Agent", "Bitacora26")
                    .header("Accept", "application/vnd.github+json").build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 404) return null;   // todavía no hay ninguna versión publicada
            if (resp.statusCode() != 200) throw new IOException("GitHub ha respondido con el código " + resp.statusCode() + ".");
            String json = resp.body();
            String etiqueta = campoTextoJson(json, "tag_name", 0);
            if (etiqueta == null) return null;
            String version = etiqueta.replaceAll("^[^0-9]+", "");
            String notas = campoTextoJson(json, "body", 0);
            notas = notas == null ? "" : notas.trim();
            int posArchivo = json.indexOf("\"name\":\"" + ARCHIVO_ACTUALIZACION + "\"");
            if (posArchivo < 0) posArchivo = json.indexOf("\"name\": \"" + ARCHIVO_ACTUALIZACION + "\"");
            String url = posArchivo < 0 ? null : campoTextoJson(json, "browser_download_url", posArchivo);
            if (url == null || !url.startsWith("https://github.com/ea4ixr/Bitacora-26/"))
                return new VersionNueva(version, "", "", -1, notas);   // esa versión no trae archivo de actualización
            String digest = campoTextoJson(json, "digest", posArchivo);
            String sha = digest != null && digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(java.util.Locale.ROOT) : "";
            long tamano = campoNumeroJson(json, "size", posArchivo);
            return new VersionNueva(version, url, sha, tamano, notas);
        }
    }

    /** Botón "Buscar actualizaciones" de la ventana "Información de la aplicación". */
    private JButton crearBotonBuscarActualizaciones(JDialog ventanaInformacion) {
        JButton boton = new JButton("Buscar actualizaciones");
        boton.setFocusPainted(false);
        boton.setToolTipText("Mira en GitHub si hay una versión más nueva de " + NOMBRE_VISIBLE);
        boton.addActionListener(e -> {
            String instalada = versionInstalada();
            if (carpetaPrograma() == null || instalada.isEmpty()) {
                JOptionPane.showMessageDialog(ventanaInformacion,
                        "Las actualizaciones solo funcionan en el programa empaquetado (Bitacora 26.exe).",
                        "Buscar actualizaciones", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            boton.setEnabled(false);
            boton.setText("Buscando…");
            Thread t = new Thread(() -> {
                VersionNueva ultima = null;
                Exception error = null;
                try { ultima = consultarUltimaVersion(); } catch (Exception ex) { error = ex; }
                final VersionNueva u = ultima;
                final Exception err = error;
                SwingUtilities.invokeLater(() -> {
                    boton.setText("Buscar actualizaciones");
                    boton.setEnabled(true);
                    if (err != null) {
                        JOptionPane.showMessageDialog(ventanaInformacion,
                                "No se ha podido comprobar si hay una versión nueva.\n"
                                        + "Revisa que el ordenador tenga conexión a Internet."
                                        + (err.getMessage() == null ? "" : "\n\n" + err.getMessage()),
                                "Buscar actualizaciones", JOptionPane.WARNING_MESSAGE);
                    } else if (u == null || compararVersiones(u.version(), instalada) <= 0) {
                        JOptionPane.showMessageDialog(ventanaInformacion,
                                "Ya tienes la última versión de " + NOMBRE_VISIBLE + " (" + instalada + ").",
                                "Buscar actualizaciones", JOptionPane.INFORMATION_MESSAGE);
                    } else if (u.url().isEmpty()) {
                        JOptionPane.showMessageDialog(ventanaInformacion,
                                "Hay una versión nueva (" + u.version() + "), pero no se puede instalar sola.\n"
                                        + "Descárgala desde la página de GitHub:\nhttps://github.com/ea4ixr/Bitacora-26/releases/latest",
                                "Buscar actualizaciones", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        ventanaInformacion.dispose();
                        preguntarActualizar(u);
                    }
                });
            }, "BuscarActualizacion");
            t.setDaemon(true);
            t.start();
        });
        return boton;
    }

    /** Versión mínima para sincronizar con el radioclub que exige la última versión publicada (null = la instalada vale). */
    volatile String versionMinimaExigida;
    private volatile boolean versionMinimaComprobada;

    /** Versión mínima escrita en las notas de la última versión de GitHub, en una línea "VERSION MINIMA: 1.12" (o null). */
    static String versionMinimaDe(VersionNueva v) {
        if (v == null || v.notas() == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?iu)VERSI[OÓ]N\\s+M[IÍ]NIMA\\s*:\\s*v?([0-9]+(?:\\.[0-9]+)*)").matcher(v.notas());
        return m.find() ? m.group(1) : null;
    }

    /** Mira una vez por sesión si la versión instalada es más antigua que la mínima exigida. Sin conexión no bloquea. */
    void comprobarVersionMinima() {
        if (versionMinimaComprobada) return;
        versionMinimaComprobada = true;
        try {
            String minima = versionMinimaDe(consultarUltimaVersion());
            String instalada = versionInstalada();
            if (minima != null && !instalada.isEmpty() && compararVersiones(instalada, minima) < 0) versionMinimaExigida = minima;
        } catch (Exception ex) {
            System.err.println("[ACTUALIZAR] No se ha podido comprobar la versión mínima: " + ex.getMessage());
        }
    }

    /** Al arrancar: avisa de cómo fue la última actualización y mira (en segundo plano) si hay otra nueva. */
    private void comprobarActualizacionAlArrancar() {
        mostrarResultadoActualizacionAnterior();
        String instalada = versionInstalada();
        if (carpetaPrograma() == null || instalada.isEmpty()) return;   // desde IntelliJ no se actualiza
        Thread t = new Thread(() -> {
            try {
                VersionNueva ultima = consultarUltimaVersion();
                String minima = versionMinimaDe(ultima);
                versionMinimaComprobada = true;
                if (minima != null && compararVersiones(instalada, minima) < 0) {
                    versionMinimaExigida = minima;
                    SwingUtilities.invokeLater(() -> {
                        radioclub.actualizarEstadoSync("Radioclub: sin sincronizar. Actualiza el programa (hace falta la versión " + minima + ")", new Color(255, 110, 90));
                        JOptionPane.showMessageDialog(this, "Esta actualización es obligatoria para seguir compartiendo con el radioclub.\n\n"
                                        + "Con tu versión (" + instalada + ") ya no se sincroniza con el radioclub: hace falta la " + minima + " o una más nueva.\n"
                                        + "El resto del programa sigue funcionando.",
                                "Actualización obligatoria", JOptionPane.WARNING_MESSAGE);
                    });
                }
                VersionNueva nueva = ultima == null || ultima.url().isEmpty() || compararVersiones(ultima.version(), instalada) <= 0 ? null : ultima;
                if (nueva != null) SwingUtilities.invokeLater(() -> preguntarActualizar(nueva));
            } catch (Exception ex) {
                System.err.println("[ACTUALIZAR] No se ha podido comprobar si hay versión nueva: " + ex.getMessage());
            }
        }, "ComprobarActualizacion");
        t.setDaemon(true);
        t.start();
    }

    /** Si en el arranque anterior se hizo una actualización, dice cómo ha ido y borra los archivos temporales. */
    private void mostrarResultadoActualizacionAnterior() {
        File tmp = carpetaTemporalActualizacion();
        File resultado = new File(tmp, "resultado.txt");
        if (!resultado.isFile()) return;
        String texto = "";
        try { texto = Files.readString(resultado.toPath(), StandardCharsets.UTF_8).replace("﻿", "").trim(); } catch (Exception ignored) { }
        borrarCarpetaEntera(tmp);
        String[] lineas = texto.split("\\R", 2);
        if (lineas[0].trim().equals("OK")) {
            String v = lineas.length > 1 ? lineas[1].trim() : versionInstalada();
            JOptionPane.showMessageDialog(this,
                    "Bitácora 26 se ha actualizado correctamente a la versión " + v + ".",
                    "Actualización terminada", JOptionPane.INFORMATION_MESSAGE);
        } else if (lineas[0].trim().equals("ERROR")) {
            String motivo = lineas.length > 1 ? lineas[1].trim() : "";
            JOptionPane.showMessageDialog(this,
                    "No se ha podido terminar la actualización.\n\n"
                            + (motivo.isEmpty() ? "" : "Motivo: " + motivo + "\n\n")
                            + "El programa sigue funcionando con la versión anterior.\n"
                            + "Se volverá a ofrecer la próxima vez que lo abras.",
                    "Actualización", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static void borrarCarpetaEntera(File carpeta) {
        File[] hijos = carpeta.listFiles();
        if (hijos != null) for (File h : hijos) {
            if (h.isDirectory()) borrarCarpetaEntera(h); else //noinspection ResultOfMethodCallIgnored
                h.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        carpeta.delete();
    }

    /** Pregunta si se quiere actualizar y, si se acepta, descarga e instala la versión nueva. */
    private void preguntarActualizar(VersionNueva nueva) {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        JLabel arriba = new JLabel("<html><b>Hay una versión nueva de Bitácora 26.</b><br><br>"
                + "Versión instalada: " + versionInstalada() + "<br>"
                + "Versión nueva: <b>" + nueva.version() + "</b></html>");
        panel.add(arriba, BorderLayout.NORTH);
        if (!nueva.notas().isEmpty()) {
            JTextArea notas = new JTextArea(nueva.notas(), 8, 46);
            notas.setEditable(false);
            notas.setLineWrap(true);
            notas.setWrapStyleWord(true);
            notas.setCaretPosition(0);
            JScrollPane sp = new JScrollPane(notas);
            sp.setBorder(BorderFactory.createTitledBorder("Novedades"));
            panel.add(sp, BorderLayout.CENTER);
        }
        // Para actualizar hay que aceptar las condiciones de uso y la política de privacidad (como cualquier aplicación)
        JPanel abajo = new JPanel(new BorderLayout(0, 6));
        abajo.add(new JLabel("<html>¿Quieres actualizar ahora?<br>"
                + "El programa se cerrará, se actualizará y se volverá a abrir solo.<br>"
                + "Tus contactos y tu configuración no se tocan.<br>"
                + "Si estás anotando un contacto, guárdalo antes.</html>"), BorderLayout.NORTH);
        JCheckBox chkCondiciones = new JCheckBox("<html>He leído y acepto las <b>condiciones de uso</b> y la <b>política de privacidad</b> de Bitácora 26.</html>");
        JButton bLeerCond = new JButton("Leer las condiciones de uso"), bLeerPriv = new JButton("Leer la política de privacidad");
        bLeerCond.addActionListener(e -> { try { Desktop.getDesktop().browse(new java.net.URI("https://bitacora26.es/condiciones.html")); } catch (Exception ignored) { } });
        bLeerPriv.addActionListener(e -> { try { Desktop.getDesktop().browse(new java.net.URI("https://bitacora26.es/privacidad.html")); } catch (Exception ignored) { } });
        JPanel leer = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        leer.add(bLeerCond);
        leer.add(bLeerPriv);
        abajo.add(leer, BorderLayout.CENTER);
        abajo.add(chkCondiciones, BorderLayout.SOUTH);
        panel.add(abajo, BorderLayout.SOUTH);
        Object[] opciones = {"Actualizar ahora", "Ahora no"};
        while (true) {
            int r = JOptionPane.showOptionDialog(this, panel, "Actualización disponible",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, opciones, opciones[0]);
            if (r != 0) return;
            if (chkCondiciones.isSelected()) break;
            JOptionPane.showMessageDialog(this, "Para actualizar tienes que aceptar las condiciones de uso y la política de privacidad.\n"
                    + "Si no las aceptas, el programa no se actualiza.", "Actualización", JOptionPane.INFORMATION_MESSAGE);
        }
        cuentaB26.aceptarCondicionesEnTodas();   // se apunta en el servidor (en segundo plano; si no hay internet, se preguntará al abrir)

        File programa = carpetaPrograma();
        if (programa == null) return;
        // ¿Se puede escribir en la carpeta del programa?
        File prueba = new File(programa, "app" + File.separator + ".prueba_escritura");
        boolean sePuede;
        try { sePuede = prueba.createNewFile() || prueba.exists(); } catch (Exception ex) { sePuede = false; }
        //noinspection ResultOfMethodCallIgnored
        prueba.delete();
        if (!sePuede) {
            JOptionPane.showMessageDialog(this,
                    "No se puede actualizar porque Windows no deja cambiar los archivos de esta carpeta:\n"
                            + programa.getAbsolutePath() + "\n\n"
                            + "Copia la carpeta \"Bitacora 26\" a otro sitio (por ejemplo al Escritorio)\n"
                            + "y abre el programa desde allí.",
                    "Actualización", JOptionPane.WARNING_MESSAGE);
            return;
        }
        descargarEInstalarActualizacion(nueva, programa);
    }

    /** Ventana con la barra de progreso: descarga, comprueba, prepara y lanza el ayudante que cambia los archivos. */
    private void descargarEInstalarActualizacion(VersionNueva nueva, File programa) {
        JDialog dialogo = new JDialog(this, "Actualizando Bitácora 26", true);
        dialogo.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JLabel texto = new JLabel("Descargando la versión " + nueva.version() + "…");
        JProgressBar barra = new JProgressBar(0, 100);
        barra.setStringPainted(true);
        JButton cancelar = new JButton("Cancelar");
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        p.add(texto, BorderLayout.NORTH);
        p.add(barra, BorderLayout.CENTER);
        JPanel pie = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        pie.add(cancelar);
        p.add(pie, BorderLayout.SOUTH);
        dialogo.setContentPane(p);
        dialogo.setSize(420, 150);
        dialogo.setLocationRelativeTo(this);

        java.util.concurrent.atomic.AtomicBoolean cancelado = new java.util.concurrent.atomic.AtomicBoolean(false);
        cancelar.addActionListener(e -> { cancelado.set(true); cancelar.setEnabled(false); texto.setText("Cancelando…"); });

        SwingWorker<File, Integer> trabajo = new SwingWorker<>() {
            @Override protected File doInBackground() throws Exception {
                File tmp = carpetaTemporalActualizacion();
                borrarCarpetaEntera(tmp);
                if (!tmp.mkdirs() && !tmp.isDirectory()) throw new IOException("No se puede crear la carpeta temporal.");
                File zip = new File(tmp, "descarga.zip");
                // 1) Descargar
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                try (HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL).build()) {
                    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(nueva.url()))
                            .header("User-Agent", "Bitacora26").build();
                    HttpResponse<java.io.InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() != 200) throw new IOException("GitHub ha respondido con el código " + resp.statusCode() + ".");
                    long total = resp.headers().firstValueAsLong("Content-Length").orElse(nueva.tamano());
                    try (java.io.InputStream in = resp.body(); java.io.OutputStream out = new java.io.FileOutputStream(zip)) {
                        byte[] buf = new byte[65536];
                        long leidos = 0;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            if (cancelado.get()) return null;
                            out.write(buf, 0, n);
                            md.update(buf, 0, n);
                            leidos += n;
                            if (total > 0) publish((int) Math.min(100, leidos * 100 / total));
                        }
                    }
                }
                // 2) Comprobar que ha llegado entero
                if (nueva.tamano() > 0 && zip.length() != nueva.tamano())
                    throw new IOException("El archivo descargado no está completo.");
                if (!nueva.sha256().isEmpty()) {
                    StringBuilder hex = new StringBuilder();
                    for (byte b : md.digest()) hex.append(String.format("%02x", b));
                    if (!hex.toString().equals(nueva.sha256()))
                        throw new IOException("El archivo descargado está dañado (no coincide la comprobación).");
                }
                if (cancelado.get()) return null;
                // 3) Descomprimir en la carpeta temporal "nuevos"
                SwingUtilities.invokeLater(() -> { texto.setText("Preparando la actualización…"); barra.setIndeterminate(true); });
                File nuevos = new File(tmp, "nuevos");
                java.util.List<String> archivos = new ArrayList<>();
                boolean traePrograma = false;
                java.nio.file.Path base = nuevos.toPath().toAbsolutePath().normalize();
                try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zip))) {
                    java.util.zip.ZipEntry ent;
                    while ((ent = zin.getNextEntry()) != null) {
                        String nombre = ent.getName().replace('\\', '/');
                        while (nombre.startsWith("/")) nombre = nombre.substring(1);
                        if (nombre.isEmpty() || nombre.contains("..") || nombre.contains(":")) continue;
                        java.nio.file.Path destino = base.resolve(nombre).normalize();
                        if (!destino.startsWith(base)) continue;
                        if (ent.isDirectory() || nombre.endsWith("/")) { Files.createDirectories(destino); continue; }
                        Files.createDirectories(destino.getParent());
                        Files.copy(zin, destino, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        archivos.add(nombre.replace('/', '\\'));
                        if (nombre.toLowerCase(java.util.Locale.ROOT).startsWith("app/") && nombre.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                            traePrograma = true;
                    }
                }
                if (archivos.isEmpty() || !traePrograma) throw new IOException("El archivo de actualización no es válido.");
                // 4) Preparar el ayudante que cambiará los archivos cuando el programa se haya cerrado
                return escribirAyudanteActualizacion(tmp, nuevos, archivos, programa, nueva.version());
            }
            @Override protected void process(java.util.List<Integer> trozos) {
                barra.setValue(trozos.get(trozos.size() - 1));
            }
            @Override protected void done() {
                File ayudante;
                try {
                    ayudante = get();
                } catch (Exception ex) {
                    dialogo.dispose();
                    Throwable causa = ex.getCause() != null ? ex.getCause() : ex;
                    borrarCarpetaEntera(carpetaTemporalActualizacion());
                    JOptionPane.showMessageDialog(LibroGuardiaUI.this,
                            "No se ha podido descargar la actualización."
                                    + (causa.getMessage() == null ? "" : "\n\n" + causa.getMessage())
                                    + "\n\nEl programa sigue funcionando con la versión actual.",
                            "Actualización", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                dialogo.dispose();
                if (ayudante == null) {   // cancelado
                    borrarCarpetaEntera(carpetaTemporalActualizacion());
                    return;
                }
                try {
                    lanzarAyudanteActualizacion(ayudante);
                } catch (Exception ex) {
                    borrarCarpetaEntera(carpetaTemporalActualizacion());
                    JOptionPane.showMessageDialog(LibroGuardiaUI.this,
                            "No se ha podido iniciar la actualización.\n\n" + ex.getMessage(),
                            "Actualización", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                // El programa se cierra como si se pulsara la X (guarda y sincroniza lo pendiente)
                LibroGuardiaUI.this.dispatchEvent(new java.awt.event.WindowEvent(LibroGuardiaUI.this, java.awt.event.WindowEvent.WINDOW_CLOSING));
            }
        };
        trabajo.execute();
        dialogo.setVisible(true);
    }

    /** Texto entre comillas simples para PowerShell. */
    private static String textoPowerShell(String s) {
        return "'" + s.replace("'", "''").replace("‘", "‘‘").replace("’", "’’")
                .replace("‚", "‚‚").replace("‛", "‛‛") + "'";
    }

    /** Escribe el ayudante (PowerShell): espera a que se cierre el programa, cambia los archivos (con copia
     *  de seguridad, por si algo falla) y vuelve a abrir el programa. */
    private static File escribirAyudanteActualizacion(File tmp, File nuevos, java.util.List<String> archivos,
                                                      File programa, String version) throws IOException {
        long pid = ProcessHandle.current().pid();
        String exe = System.getProperty("jpackage.app-path");
        StringBuilder lista = new StringBuilder();
        for (String a : archivos) {
            if (lista.length() > 0) lista.append(",\r\n  ");
            lista.append(textoPowerShell(a));
        }
        String ps = String.join("\r\n",
                "# Ayudante de actualización de Bitácora 26 (se crea y se borra solo)",
                "$ErrorActionPreference = 'Stop'",
                "$pidPrograma = " + pid,
                "$nuevos    = " + textoPowerShell(nuevos.getAbsolutePath()),
                "$destino   = " + textoPowerShell(programa.getAbsolutePath()),
                "$respaldo  = " + textoPowerShell(new File(tmp, "respaldo").getAbsolutePath()),
                "$resultado = " + textoPowerShell(new File(tmp, "resultado.txt").getAbsolutePath()),
                "$exe       = " + textoPowerShell(exe),
                "$version   = " + textoPowerShell(version),
                "$archivos  = @(",
                "  " + lista,
                ")",
                "function Escribir($texto) { [System.IO.File]::WriteAllText($resultado, $texto, (New-Object System.Text.UTF8Encoding($false))) }",
                "# 1) Esperar a que el programa se cierre (como mucho 5 minutos)",
                "$p = Get-Process -Id $pidPrograma -ErrorAction SilentlyContinue",
                "if ($p) { $null = $p.WaitForExit(300000) }",
                "if (Get-Process -Id $pidPrograma -ErrorAction SilentlyContinue) {",
                "  Escribir (\"ERROR`nEl programa no se ha cerrado a tiempo.\")",
                "  exit 1",
                "}",
                "Start-Sleep -Milliseconds 800",
                "# 2) Cambiar los archivos, guardando antes los antiguos",
                "$hechos = New-Object System.Collections.ArrayList",
                "try {",
                "  foreach ($rel in $archivos) {",
                "    $origen = Join-Path $nuevos $rel",
                "    $dest = Join-Path $destino $rel",
                "    # Si el archivo no ha cambiado (por ejemplo, las DLL de JACOB), no se toca",
                "    if ((Test-Path -LiteralPath $dest) -and ((Get-Item -LiteralPath $dest).Length -eq (Get-Item -LiteralPath $origen).Length) -and",
                "        ((Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash -eq (Get-FileHash -LiteralPath $origen -Algorithm SHA256).Hash)) { continue }",
                "    $carpeta = Split-Path $dest -Parent",
                "    if (-not (Test-Path -LiteralPath $carpeta)) { New-Item -ItemType Directory -Path $carpeta -Force | Out-Null }",
                "    if (Test-Path -LiteralPath $dest) {",
                "      $resp = Join-Path $respaldo $rel",
                "      New-Item -ItemType Directory -Path (Split-Path $resp -Parent) -Force | Out-Null",
                "      Copy-Item -LiteralPath $dest -Destination $resp -Force",
                "    }",
                "    $null = $hechos.Add($rel)",
                "    $ok = $false",
                "    for ($i = 0; $i -lt 30 -and -not $ok; $i++) {",
                "      try { Copy-Item -LiteralPath $origen -Destination $dest -Force; $ok = $true } catch { Start-Sleep -Seconds 1 }",
                "    }",
                "    if (-not $ok) { throw \"No se ha podido reemplazar $rel\" }",
                "  }",
                "  Escribir (\"OK`n\" + $version)",
                "} catch {",
                "  # Algo ha fallado: se deja todo como estaba",
                "  foreach ($rel in $hechos) {",
                "    $resp = Join-Path $respaldo $rel",
                "    $dest = Join-Path $destino $rel",
                "    try {",
                "      if (Test-Path -LiteralPath $resp) { Copy-Item -LiteralPath $resp -Destination $dest -Force }",
                "      else { Remove-Item -LiteralPath $dest -Force -ErrorAction SilentlyContinue }",
                "    } catch { }",
                "  }",
                "  Escribir (\"ERROR`n\" + $_.Exception.Message)",
                "}",
                "# 3) Volver a abrir el programa",
                "Start-Process -FilePath $exe -WorkingDirectory $destino",
                "");
        File ayudante = new File(tmp, "actualizar.ps1");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] cuerpo = ps.getBytes(StandardCharsets.UTF_8);
        byte[] todo = new byte[bom.length + cuerpo.length];
        System.arraycopy(bom, 0, todo, 0, bom.length);
        System.arraycopy(cuerpo, 0, todo, bom.length, cuerpo.length);
        Files.write(ayudante.toPath(), todo);
        return ayudante;
    }

    /** Lanza el ayudante de PowerShell, que sigue funcionando cuando el programa se cierra. */
    private static void lanzarAyudanteActualizacion(File ayudante) throws IOException {
        String raiz = System.getenv("SystemRoot");
        File psExe = raiz == null ? null : new File(raiz, "System32\\WindowsPowerShell\\v1.0\\powershell.exe");
        String comando = psExe != null && psExe.isFile() ? psExe.getAbsolutePath() : "powershell.exe";
        ProcessBuilder pb = new ProcessBuilder(comando, "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-File", ayudante.getAbsolutePath());
        pb.directory(ayudante.getParentFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.start();
    }

    /** Una tarjeta de "Información de la aplicación": título pequeño, logotipo, nombre e indicativo con enlace. */
    private static JPanel tarjetaAcercaDe(String titulo, String recursoLogo, String nombre, String indicativos,
                                          String url, String ayudaEnlace, Color azul, java.util.function.Consumer<String> abrirEnlace) {
        JPanel t = new JPanel();
        t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
        t.setBackground(TEMA_FONDO_2);
        t.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(TEMA_LINEA),
                BorderFactory.createEmptyBorder(12, 16, 14, 16)));
        JLabel lTitulo = new JLabel(titulo);
        lTitulo.setFont(new Font("Arial", Font.BOLD, 11));
        lTitulo.setForeground(TEMA_AMBAR);
        JLabel lLogo = new JLabel();
        ImageIcon logo = iconoRecurso(recursoLogo, 130);
        if (logo != null) {
            if (logo.getIconWidth() > 210) logo = new ImageIcon(logo.getImage().getScaledInstance(210,
                    Math.max(1, logo.getIconHeight() * 210 / logo.getIconWidth()), Image.SCALE_SMOOTH));
            lLogo.setIcon(logo);
        }
        lLogo.setPreferredSize(new Dimension(210, 134));
        lLogo.setHorizontalAlignment(SwingConstants.CENTER);
        JLabel lNombre = new JLabel(nombre);
        lNombre.setFont(new Font("Arial", Font.BOLD, 16));
        lNombre.setForeground(Color.WHITE);
        JLabel lInd = new JLabel(indicativos);
        lInd.setFont(new Font("Arial", Font.BOLD, 13));
        lInd.setForeground(azul);
        final Font fuenteInd = lInd.getFont();
        for (JLabel l : new JLabel[]{lLogo, lInd}) {
            l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            l.setToolTipText(ayudaEnlace);
            l.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e)) abrirEnlace.accept(url); }
                // Subrayado con la letra (no con HTML), para que el texto no se mueva al pasar el ratón
                @Override public void mouseEntered(MouseEvent e) {
                    if (l == lInd) l.setFont(l.getFont().deriveFont(java.util.Map.of(java.awt.font.TextAttribute.UNDERLINE, java.awt.font.TextAttribute.UNDERLINE_ON)));
                }
                @Override public void mouseExited(MouseEvent e) {
                    if (l == lInd) l.setFont(fuenteInd);
                }
            });
        }
        for (JComponent c : new JComponent[]{lTitulo, lLogo, lNombre, lInd}) c.setAlignmentX(Component.CENTER_ALIGNMENT);
        t.add(lTitulo);
        t.add(Box.createVerticalStrut(10));
        t.add(lLogo);
        t.add(Box.createVerticalStrut(10));
        t.add(lNombre);
        t.add(Box.createVerticalStrut(3));
        t.add(lInd);
        return t;
    }

    // ===================== FIN ACERCA DE =====================

    // ======================= MAPA (enlace con la ventana) =======================

    /** Pide actualizar el mapa con lo que hay escrito en el formulario (con una pequeña espera). */
    private void programarMapaDesdeFormulario() {
        if (mapaQso == null || txtGrid == null || txtQth == null) return;
        // Si el Grid lo puso el programa a partir del QTH y el QTH ha cambiado, se quita para buscar el nuevo
        String qthAhora = txtQth.getText().trim();
        if (gridAutomatico != null && !qthAhora.equalsIgnoreCase(qthDelGridAutomatico)) {
            if (txtGrid.getText().trim().equalsIgnoreCase(gridAutomatico)) {
                String g = gridAutomatico;
                gridAutomatico = null;
                SwingUtilities.invokeLater(() -> {
                    if (txtGrid.getText().trim().equalsIgnoreCase(g)) txtGrid.setText("");
                });
            } else {
                gridAutomatico = null;   // el Grid lo ha cambiado el usuario: ya no se toca
            }
        }
        if (temporizadorMapa == null) {
            temporizadorMapa = new javax.swing.Timer(700, e -> {
                Object b = cbBanda == null ? null : cbBanda.getSelectedItem();
                // El QTH se busca en internet al salir de la casilla, no mientras se escribe (normas de OpenStreetMap)
                String qthMapa = txtQth.isFocusOwner() ? "" : txtQth.getText().trim();
                actualizarMapa(txtIndicativo.getText().trim(), txtGrid.getText().trim(), qthMapa,
                        b == null ? "" : b.toString(), true);
                actualizarBandera(txtIndicativo.getText().trim(), b == null ? "" : b.toString());
            });
            temporizadorMapa.setRepeats(false);
        }
        temporizadorMapa.restart();
    }

    /** Muestra en el mapa el contacto de la fila seleccionada de la tabla. */
    /** Indicativo del contacto seleccionado en la tabla (vacío si no hay ninguno). */
    private String indicativoFilaSeleccionada() {
        try {
            int fila = tablaQSOs.getSelectedRow();
            if (fila < 0) return "";
            int m = tablaQSOs.convertRowIndexToModel(fila);
            javax.swing.table.TableModel modelo = tablaQSOs.getModel();
            Object v = modelo.getValueAt(m, modelo == modeloCB ? 0 : 1);
            return v == null || "null".equals(String.valueOf(v)) ? "" : String.valueOf(v).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void mostrarFilaEnMapa() {
        if (mapaQso == null) return;
        int fila = tablaQSOs.getSelectedRow();
        if (fila < 0) {   // sin contacto elegido: si no hay nada escrito en el formulario, se quitan mapa y bandera
            if (txtIndicativo == null || txtIndicativo.getText().trim().isEmpty()) {
                actualizarMapa("", "", "");
                actualizarBandera("", "");
                if (panelTrabajado != null) panelTrabajado.mostrar("");
            }
            return;
        }
        int m = tablaQSOs.convertRowIndexToModel(fila);
        javax.swing.table.TableModel modelo = tablaQSOs.getModel();
        String ind, qth, grid, banda;
        if (modelo == modeloCB) {
            ind = String.valueOf(modelo.getValueAt(m, 0));
            qth = String.valueOf(modelo.getValueAt(m, 2));
            grid = "";
            banda = "CB";
        } else if (modelo == modeloPuntos) {
            ind = String.valueOf(modelo.getValueAt(m, 1));
            qth = String.valueOf(modelo.getValueAt(m, 3));
            grid = String.valueOf(modelo.getValueAt(m, 4));
            banda = "";
        } else {
            ind = String.valueOf(modelo.getValueAt(m, 1));
            qth = String.valueOf(modelo.getValueAt(m, 11));
            grid = String.valueOf(modelo.getValueAt(m, 12));
            banda = String.valueOf(modelo.getValueAt(m, 4));
        }
        actualizarMapa(ind, "null".equals(grid) ? "" : grid, "null".equals(qth) ? "" : qth,
                "null".equals(banda) ? "" : banda, false);
        actualizarBandera(ind, "null".equals(banda) ? "" : banda);
    }

    /**
     * Calcula mi posición (Grid de mi ficha; si no, mi QTH) y la del corresponsal
     * (su Grid; si no, su QTH) y las pinta. Las búsquedas por internet van en segundo plano.
     */
    private void actualizarMapa(String indicativo, String grid, String qth) {
        actualizarMapa(indicativo, grid, qth, "", false);
    }

    /**
     * @param banda      banda del contacto (para saber el país en CB)
     * @param rellenarGrid si es true (formulario) y el Grid está vacío, se rellena con el del QTH encontrado
     */
    private void actualizarMapa(String indicativo, String grid, String qth, String banda, boolean rellenarGrid) {
        if (mapaQso == null) return;
        String[] paisInd = paisDeIndicativo(indicativo, banda);
        final String isoPais = paisInd != null ? paisInd[0] : null;   // sin país conocido: se busca en todo el mundo
        // Contacto de CB: banda CB o un apodo (no es un indicativo de radioaficionado)
        final boolean esContactoCb = "CB".equalsIgnoreCase(banda)
                || (indicativo != null && !indicativo.isBlank() && !esIndicativoRadioaficionado(indicativo));
        final int miTurno = secuenciaMapa.incrementAndGet();
        final String[] ficha = leerDatosOperador(operadorActual == null ? "" : operadorActual);
        hiloMapa.submit(() -> {
            double[] casa = gridALatLon(ficha[4]);
            String nombreCasa = ficha[4].trim().toUpperCase();
            if (casa == null && !ficha[3].trim().isEmpty()) {
                casa = buscarCoordenadasQth(ficha[3]);
                nombreCasa = ficha[3].trim().toUpperCase();
            }

            double[] remoto = null;
            String paisQth = null;
            String nombreRemoto = indicativo == null ? "" : indicativo.trim().toUpperCase();
            String aviso = "";
            if (gridALatLon(grid) != null) {
                remoto = gridALatLon(grid);
                nombreRemoto += (nombreRemoto.isEmpty() ? "" : "  ") + grid.trim().toUpperCase();
            } else if (qth != null && !qth.trim().isEmpty()) {
                // Solo en Banda Ciudadana se pregunta si la ciudad existe en varios países
                LugarEncontrado lugar = buscarLugarQth(qth, isoPais, rellenarGrid && esContactoCb);
                remoto = lugar == null ? null : new double[]{lugar.lat(), lugar.lon()};
                if (lugar != null && lugar.pais() != null && !lugar.pais().isEmpty()) paisQth = lugar.pais();
                if (remoto == null) {
                    aviso = "No encuentro \"" + qth.trim().toUpperCase() + "\" (o no hay internet)";
                } else {
                    nombreRemoto += (nombreRemoto.isEmpty() ? "" : "  ") + qth.trim().toUpperCase();
                }
            }

            final double[] fCasa = casa, fRemoto = remoto;
            final String fNomCasa = nombreCasa, fNomRemoto = nombreRemoto, fAviso = aviso;
            final boolean desdeQth = remoto != null && gridALatLon(grid) == null;
            // En CB (o apodos) la bandera sale del país del QTH, aunque ya haya Grid (posición ya guardada: es rápido)
            if (esContactoCb && paisQth == null && qth != null && !qth.trim().isEmpty()) {
                LugarEncontrado l = buscarLugarQth(qth, isoPais, false);
                if (l != null && l.pais() != null && !l.pais().isEmpty()) paisQth = l.pais();
            }
            final String fPaisQth = paisQth;
            SwingUtilities.invokeLater(() -> {
                if (miTurno != secuenciaMapa.get()) return; // ya se ha pedido otra cosa más nueva
                mapaQso.setCasa(fCasa, fNomCasa);
                mapaQso.setRemoto(fRemoto, fNomRemoto, fAviso);
                // Grid automático: solo en el formulario, si sigue vacío y el QTH no ha cambiado mientras tanto
                if (rellenarGrid && desdeQth && txtGrid != null && txtQth != null
                        && txtGrid.getText().trim().isEmpty()
                        && txtQth.getText().trim().equalsIgnoreCase(qth == null ? "" : qth.trim())) {
                    String gridAuto = latLonAGrid(fRemoto[0], fRemoto[1]).toUpperCase();
                    gridAutomatico = gridAuto;     // recordar que lo puso el programa
                    qthDelGridAutomatico = txtQth.getText().trim();
                    txtGrid.setText(gridAuto);
                }
                // En CB (o apodos) la bandera es la del país donde está el QTH (o la de la división CB)
                if (esContactoCb) {
                    if (fPaisQth != null) {
                        mostrarBanderaIso(fPaisQth, fPaisQth);
                    } else if (paisInd != null) {
                        mostrarBanderaIso(paisInd[0], paisInd[1]);
                    } else if (lblBandera != null) {
                        lblBandera.setIcon(null);
                        lblBandera.setText("");
                        lblBandera.setToolTipText(null);
                    }
                }
            });
        });
    }

    // ---------------- BANDERA DEL PAÍS ----------------

    // Prefijo DXCC -> {ISO, entidad}. Se carga una vez del recurso prefijos_dxcc.txt
    private static Map<String, String[]> prefijosDxcc;
    private static int largoMaxPrefijo = 0;
    private static final Map<String, ImageIcon> cacheBanderas = new HashMap<>();

    // Banda Ciudadana: número de división -> país (ISO). Se pueden añadir más.
    private static final Map<String, String> DIVISIONES_CB = Map.of(
            "1", "IT", "13", "DE", "14", "FR", "26", "NL", "30", "ES");

    private static synchronized void cargarPrefijosDxcc() {
        if (prefijosDxcc != null) return;
        prefijosDxcc = new HashMap<>();
        try (java.io.InputStream is = LibroGuardiaUI.class.getResourceAsStream("/prefijos_dxcc.txt")) {
            if (is == null) return;
            BufferedReader br = new BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8));
            String l;
            while ((l = br.readLine()) != null) {
                if (l.startsWith("#") || l.isBlank()) continue;
                String[] p = l.split("\\|", 3);
                if (p.length < 3) continue;
                prefijosDxcc.put(p[0], new String[]{p[1], p[2]});
                largoMaxPrefijo = Math.max(largoMaxPrefijo, p[0].length());
            }
        } catch (Exception e) {
            System.err.println("No se pudo cargar la lista DXCC: " + e.getMessage());
        }
    }

    /** Indicativo de radioaficionado "de verdad": lleva algún número y termina en letra (EA4IXR, EA8/DL1ABC/P). */
    static boolean esIndicativoRadioaficionado(String indicativo) {
        if (indicativo == null) return false;
        String c = indicativo.trim().toUpperCase();
        for (String parte : c.split("/")) {
            if (parte.matches("[A-Z0-9]{1,3}[0-9][A-Z0-9]*[A-Z]")) return true;
        }
        return false;
    }

    /** País de un indicativo: {ISO, entidad} o null. En CB se usa el número de división (30 = España...). */
    static String[] paisDeIndicativo(String indicativo, String banda) {
        if (indicativo == null) return null;
        String c = indicativo.trim().toUpperCase();
        if (c.length() < 2) return null;

        if ("CB".equalsIgnoreCase(banda)) {
            Matcher mcb = Pattern.compile("^(\\d{1,3})[A-Z]").matcher(c);
            if (mcb.find()) {
                String iso = DIVISIONES_CB.get(mcb.group(1));
                return iso == null ? null : new String[]{iso, "División CB " + mcb.group(1)};
            }
            return null;
        }

        // Apodos de CB y textos que no son indicativos: no se sabe el país por el prefijo
        if (!esIndicativoRadioaficionado(c)) return null;

        // Indicativos con barra: EA8/DL1ABC, DL1ABC/P, DL1ABC/EA8 -> se elige la parte que es el prefijo
        String base = c;
        if (c.contains("/")) {
            String elegido = null;
            for (String parte : c.split("/")) {
                if (parte.isEmpty() || parte.matches("P|M|MM|AM|QRP|A|\\d")) continue;
                if (elegido == null || parte.length() < elegido.length()) elegido = parte;
            }
            if (elegido == null) return null;
            base = elegido;
        }

        cargarPrefijosDxcc();
        for (int n = Math.min(largoMaxPrefijo, base.length()); n >= 1; n--) {
            String[] r = prefijosDxcc.get(base.substring(0, n));
            if (r != null) return r;
        }
        return null;
    }

    static ImageIcon banderaIso(String iso) {
        String clave = iso.toLowerCase();
        if (cacheBanderas.containsKey(clave)) return cacheBanderas.get(clave);
        ImageIcon icono = null;
        try (java.io.InputStream is = LibroGuardiaUI.class.getResourceAsStream("/banderas.zip")) {
            if (is != null) {
                java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(is);
                java.util.zip.ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if (e.getName().equalsIgnoreCase(clave + ".png")) {
                        BufferedImage img = ImageIO.read(zip);
                        if (img != null) {
                            int alto = 24;   // bandera pequeña, con el nombre del país debajo
                            int ancho = Math.max(1, img.getWidth() * alto / img.getHeight());
                            if (ancho > 70) { ancho = 70; alto = Math.max(1, img.getHeight() * ancho / img.getWidth()); }
                            BufferedImage conBorde = new BufferedImage(ancho + 2, alto + 2, BufferedImage.TYPE_INT_ARGB);
                            Graphics2D g = conBorde.createGraphics();
                            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                            g.drawImage(img, 1, 1, ancho, alto, null);
                            g.setColor(new Color(120, 120, 120));
                            g.drawRect(0, 0, ancho + 1, alto + 1);
                            g.dispose();
                            icono = new ImageIcon(conBorde);
                        }
                        break;
                    }
                }
            }
        } catch (Exception ex) {
            System.err.println("No se pudo cargar la bandera " + iso + ": " + ex.getMessage());
        }
        cacheBanderas.put(clave, icono);
        return icono;
    }

    /** Muestra la bandera de un país (ISO). */
    private void mostrarBanderaIso(String iso, String descripcion) {
        if (lblBandera == null || iso == null || iso.isEmpty()) return;
        String nombrePais = new java.util.Locale("", iso).getDisplayCountry(new java.util.Locale("es", "ES"));
        if (nombrePais == null || nombrePais.isEmpty()) nombrePais = iso;
        lblBandera.setIcon(banderaIso(iso));
        lblBandera.setText(nombrePais.toUpperCase());
        lblBandera.setToolTipText(descripcion);
    }

    /** Muestra la bandera y el nombre del país del indicativo (vacío si no se sabe). */
    private void actualizarBandera(String indicativo, String banda) {
        if (lblBandera == null) return;
        if (indicativo == null || indicativo.isBlank()) {   // sin indicativo, sin bandera
            lblBandera.setIcon(null);
            lblBandera.setText("");
            lblBandera.setToolTipText(null);
            return;
        }
        // Contactos de CB (banda CB o apodos): la bandera la pone el mapa según el país del QTH
        if ("CB".equalsIgnoreCase(banda)
                || (indicativo != null && !indicativo.isBlank() && !esIndicativoRadioaficionado(indicativo))) return;
        String[] pais = paisDeIndicativo(indicativo, banda);
        if (pais == null) {
            lblBandera.setIcon(null);
            lblBandera.setText("");
            lblBandera.setToolTipText(null);
            return;
        }
        String nombrePais = new java.util.Locale("", pais[0]).getDisplayCountry(new java.util.Locale("es", "ES"));
        if (nombrePais == null || nombrePais.isEmpty() || nombrePais.equalsIgnoreCase(pais[0])) nombrePais = pais[1];
        lblBandera.setIcon(banderaIso(pais[0]));
        lblBandera.setText(nombrePais.toUpperCase());
        lblBandera.setToolTipText(pais[1]);
    }

    // ===================== FIN MAPA (enlace con la ventana) =====================

    private void crearMenuSuperior() {
        JMenuBar barraMenu = new JMenuBar() {
            @Override protected void paintComponent(Graphics g) {   // fondo oscuro del tema
                g.setColor(TEMA_FONDO_2);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        barraMenu.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, TEMA_LINEA));

        // Menús: Archivo · Mi Estación · Actividades · QSL · Cluster · Ayuda
        JMenuItem itemEditarOperador = new JMenuItem("Operador (mis datos y mi cuenta)");
        itemEditarOperador.addActionListener(e -> abrirDialogoEditarOperadores());

        JMenuItem itemConfigRadioclub = new JMenuItem("Radioclub");
        itemConfigRadioclub.addActionListener(e -> radioclub.abrirDialogoConfigRadioclub());

        // ---- Archivo
        JMenu menuArchivo = new JMenu("Archivo");
        JMenuItem itemSyncContactos = new JMenuItem("Sincronizar ordenadores");
        itemSyncContactos.setToolTipText("Contactos, actividades, QSL y diplomas recibidos: los pone igual en todos tus ordenadores");
        itemSyncContactos.addActionListener(e -> { if (cuentaB26.exigirCuenta(this, "sincronizar tus ordenadores")) sincronizarContactosDesdeMenu(); });
        menuArchivo.add(itemSyncContactos);
        JMenuItem itemSyncRadioclub = new JMenuItem("Sincronizar Datos del Radioclub");
        itemSyncRadioclub.setToolTipText("Datos del radioclub, socios, actividades, contactos y directorio CB: envía lo nuevo y recoge lo de los demás socios");
        itemSyncRadioclub.addActionListener(e -> {
            if (!cuentaB26.exigirCuenta(this, "sincronizar con el radioclub")) return;
            if (!radioclub.syncActivo()) { sincronizarDesdeBoton(); return; }
            radioclub.sincronizarRadioclubConVentana();
        });
        menuArchivo.add(itemSyncRadioclub);
        JMenuItem itemPapelera = new JMenuItem("Recuperar contactos borrados");
        itemPapelera.addActionListener(e -> radioclub.abrirPapelera());
        menuArchivo.add(itemPapelera);
        menuArchivo.addSeparator();
        JMenuItem itemImportarAdi = new JMenuItem("Importar");
        itemImportarAdi.setToolTipText("Importar contactos de un archivo ADI");
        itemImportarAdi.addActionListener(e -> importarFicheroAdi());
        menuArchivo.add(itemImportarAdi);
        JMenuItem itemExportarAdi = new JMenuItem("Exportar");
        itemExportarAdi.setToolTipText("Exportar contactos a un archivo ADI");
        itemExportarAdi.addActionListener(e -> exportarFicheroAdi());
        menuArchivo.add(itemExportarAdi);
        menuArchivo.addSeparator();
        JMenuItem itemAudio = new JMenuItem("Configurar audio...");
        itemAudio.setToolTipText("Tarjeta de sonido de la emisora y canal (mono, izquierdo, derecho o automático) de la entrada y la salida");
        itemAudio.addActionListener(e -> ConfigAudio.abrir(this));
        menuArchivo.add(itemAudio);
        menuArchivo.addSeparator();
        JMenuItem itemSalir = new JMenuItem("Salir");
        // Igual que cerrar la ventana con la X
        itemSalir.addActionListener(e -> dispatchEvent(new java.awt.event.WindowEvent(this, java.awt.event.WindowEvent.WINDOW_CLOSING)));
        menuArchivo.add(itemSalir);
        barraMenu.add(menuArchivo);

        // ---- Mi Estación
        JMenu menuEstacion = new JMenu("Mi Estación");
        menuEstacion.add(itemEditarOperador);
        JMenuItem itemEmisoras = new JMenuItem("Mis emisoras");
        itemEmisoras.addActionListener(e -> cat.abrirDialogoEmisoras());
        menuEstacion.add(itemEmisoras);
        JMenuItem itemCascada = new JMenuItem("Cascada (RTL-SDR)");
        itemCascada.addActionListener(e -> cat.abrirDialogoCascada());
        menuEstacion.add(itemCascada);
        JMenuItem itemConfigQrz = new JMenuItem("Servicios online (QRZ, eQSL…)");
        itemConfigQrz.addActionListener(e -> abrirDialogoServiciosOnline());
        menuEstacion.add(itemConfigQrz);
        menuEstacion.addSeparator();
        menuEstacion.add(itemConfigRadioclub);   // los socios y las cuotas están dentro (pestaña de gestión)
        barraMenu.add(menuEstacion);

        // ---- Actividades
        JMenu menuActividades = new JMenu("Actividades");
        JMenuItem itemActividad = new JMenuItem("Nueva Actividad");
        itemActividad.addActionListener(e -> abrirDialogoGestionActividades());
        menuActividades.add(itemActividad);
        JMenuItem itemGestionarActividades = new JMenuItem("Gestionar Actividades");
        itemGestionarActividades.addActionListener(e -> abrirDialogoGestionarActividades());
        menuActividades.add(itemGestionarActividades);
        JMenuItem itemDiplomasWeb = new JMenuItem("Subir Diplomas");
        itemDiplomasWeb.addActionListener(e -> radioclub.diplomasWebOperador());
        menuActividades.addSeparator();
        menuActividades.add(itemDiplomasWeb);
        JMenuItem itemDiplomasRecibidos = new JMenuItem("Diplomas recibidos");
        itemDiplomasRecibidos.addActionListener(e -> cuentaB26.abrirDiplomasRecibidos());
        menuActividades.add(itemDiplomasRecibidos);
        barraMenu.add(menuActividades);

        barraMenu.add(cuentaB26.crearMenuQsl());   // cuenta Bitácora 26, confirmaciones y QSL

        // ---- Cluster: se abre solo al hacer clic (no al pasar el ratón con otro menú abierto); no tiene submenú
        JMenu menuCluster = new JMenu("Cluster");
        menuCluster.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                SwingUtilities.invokeLater(() -> {
                    MenuSelectionManager.defaultManager().clearSelectedPath();
                    cluster.abrirVentanaCluster();
                });
            }
        });
        barraMenu.add(menuCluster);

        JMenu menuAcercaDe = new JMenu("Ayuda");
        JMenuItem itemGuia = new JMenuItem("Guía de Uso");
        itemGuia.setToolTipText("Abre la guía de uso en el navegador (bitacora26.es)");
        itemGuia.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(new URI("https://bitacora26.es/guia.html"));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "No se pudo abrir el navegador.\nLa guía está en: https://bitacora26.es/guia.html",
                        "Guía de Uso", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        menuAcercaDe.add(itemGuia);
        JMenuItem itemWeb = new JMenuItem("Web de Bitácora 26");
        itemWeb.setToolTipText("Abre bitacora26.es en el navegador");
        itemWeb.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(new URI("https://bitacora26.es"));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "No se pudo abrir el navegador.\nLa web es: https://bitacora26.es",
                        "Web de Bitácora 26", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        JMenuItem itemInformacion = new JMenuItem("Información de la aplicación");
        itemInformacion.addActionListener(e -> abrirVentanaAcercaDe());
        menuAcercaDe.add(itemInformacion);
        barraMenu.add(menuAcercaDe);

        setJMenuBar(barraMenu);
    }

    /** Frecuencia para diplomas y QSL: "14.2" -> "14.200 MHz" (al menos 3 decimales; si ya trae "Hz", se deja). */
    static String frecuenciaParaMostrar(String f) {
        if (f == null || f.isBlank()) return "";
        String t = f.trim();
        if (t.toUpperCase().contains("HZ")) return t;
        try {
            java.math.BigDecimal b = new java.math.BigDecimal(t.replace(',', '.'));
            if (b.signum() <= 0) return "";
            b = b.stripTrailingZeros();
            if (b.scale() < 3) b = b.setScale(3);
            return b.toPlainString() + " MHz";
        } catch (Exception e) {
            return t + " MHz";
        }
    }


    static String jsonTexto(String t) {
        if (t == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    static String campoJson(String o, String k) {
        Matcher m = Pattern.compile("\"" + k + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(o);
        if (!m.find()) return "";
        String t = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        Matcher u = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(t);
        StringBuilder b = new StringBuilder();
        while (u.find()) u.appendReplacement(b, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(u.group(1), 16))));
        u.appendTail(b);
        return b.toString();
    }

    static long numeroJson(String o, String k) {
        Matcher m = Pattern.compile("\"" + k + "\"\\s*:\\s*(-?\\d+)").matcher(o);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }


    // true solo durante la conexión automática al arrancar: si la emisora no contesta, se avisa y no se insiste
    private volatile boolean conexionAutomatica = false;

    /** Conexión automática que no ha salido bien: se avisa una vez y el usuario conectará a mano. */
    private void avisarFalloCatAutomatico(String detalle) {
        ControlEmisora.EmisoraConfig enUso = cat.emisoraActiva();
        String nombre = enUso == null ? "la emisora" : "la emisora \"" + enUso.nombre + "\" (Rig " + enUso.rig + ")";
        btnCatToggle.setText("Conectar CAT");
        btnCatToggle.setBackground(new Color(0, 150, 0));
        lblEstado.setText("CAT Desconectado: no se pudo conectar con " + nombre);
        JOptionPane.showMessageDialog(this,
                "No se ha podido conectar con " + nombre + ".\n\n"
                        + "Puede que la emisora esté apagada o que la configuración no sea correcta\n"
                        + "(Mi Estación → Mis emisoras, y OmniRig).\n\n"
                        + "No se volverá a intentar: cuando la emisora esté lista, pulsa \"Conectar CAT\"."
                        + (detalle == null || detalle.isBlank() ? "" : "\n\nDetalle: " + detalle),
                "CAT sin conectar", JOptionPane.WARNING_MESSAGE);
    }

    void alternarConexionCat() {
        final boolean automatica = conexionAutomatica;
        conexionAutomatica = false;
        if (!catConectado && !cat.avisoSinEmisoraHecho) {
            ControlEmisora.EmisoraConfig enUso = cat.emisoraActiva();
            if (enUso == null || !enUso.activa) {
                Object[] opciones = {"Configurar emisora ahora", "Conectar igualmente"};
                int o = JOptionPane.showOptionDialog(this,
                        "No tienes ninguna emisora configurada, activa y en uso.\n\n"
                                + "Para ver la pestaña de la emisora con sus mandos (filtros, potencia, AGC...),\n"
                                + "configúrala en Mi Estación → Mis emisoras.\n\n"
                                + "Si conectas igualmente, el programa solo leerá la frecuencia y el modo.",
                        "Emisora sin configurar", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opciones, opciones[0]);
                if (o == 0) {
                    cat.abrirDialogoEmisoras();
                    return;
                }
                if (o != 1) return;
                cat.avisoSinEmisoraHecho = true;   // en esta sesión ya no se vuelve a preguntar
            }
        }
        if (!catConectado) {
            // Se conecta siempre al Rig de la emisora en uso (Configurar Emisoras)
            ControlEmisora.EmisoraConfig emisoraEnUso = cat.emisoraActiva();
            if (emisoraEnUso != null && emisoraEnUso.activa && emisoraEnUso.rig != rigSeleccionado) {
                rigSeleccionado = emisoraEnUso.rig;
                ConfigApp.putInt("RIG_SELECCIONADO", rigSeleccionado);
            }
            final int miSesion = ++cat.sesionCat;   // los hilos de una conexión anterior se paran solos
            hiloCat = new Thread(() -> {
                ComThread.InitSTA();
                ActiveXComponent[] omniSoltar = new ActiveXComponent[1];
                try {
                    ActiveXComponent omnirig = new ActiveXComponent(cat.idOmniRig());
                    omniSoltar[0] = omnirig;
                    String nombreRig = "Rig" + rigSeleccionado;

                    try {
                        omnirig.getProperty(nombreRig).toDispatch();
                    } catch (Exception exRig) {
                        if (automatica) {
                            catConectado = false;
                            SwingUtilities.invokeLater(() -> avisarFalloCatAutomatico("el " + nombreRig + " no existe en OmniRig"));
                            return;
                        }
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(this,
                                    "El " + nombreRig + " no existe en la versión de OmniRig instalada.\n\n" +
                                            "• OmniRig v1 solo tiene Rig 1 y Rig 2.\n" +
                                            "• OmniRig v2 tiene Rig 1 a Rig 4.\n\n" +
                                            "Por favor, cambia a un Rig válido en:\n" +
                                            "Mi Estación → Mis emisoras.",
                                    "Rig no disponible", JOptionPane.WARNING_MESSAGE);
                            btnCatToggle.setText("Conectar CAT");
                            btnCatToggle.setBackground(new Color(0, 150, 0));
                            lblEstado.setText("CAT Desconectado");
                        });
                        catConectado = false;
                        return;
                    }

                    catConectado = true;
                    if (automatica) {
                        // Conexión al arrancar: se espera (hasta 10 s) a que OmniRig diga que la emisora contesta (Status 4 = On-line)
                        boolean contesta = false;
                        String estadoTxt = "";
                        for (int i = 0; i < 20 && miSesion == cat.sesionCat && catConectado; i++) {
                            try {
                                Dispatch r0 = omnirig.getProperty(nombreRig).toDispatch();
                                if (Dispatch.get(r0, "Status").getInt() == 4) { contesta = true; break; }
                                estadoTxt = Dispatch.get(r0, "StatusStr").getString();
                            } catch (Exception ignored) { }
                            try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                        }
                        if (miSesion != cat.sesionCat || !catConectado) return;   // el usuario pulsó el botón mientras tanto
                        if (!contesta) {
                            catConectado = false;
                            cat.sesionCat++;
                            cat.registroCat("CAT automático al arrancar: la emisora no contesta (" + nombreRig + " · " + estadoTxt + ")");
                            final String det = estadoTxt.isBlank() ? "" : "OmniRig dice: " + estadoTxt;
                            SwingUtilities.invokeLater(() -> avisarFalloCatAutomatico(det));
                            return;
                        }
                    }
                    SwingUtilities.invokeLater(() -> { if (cat.esFt891EnUso()) cat.marcarVfo(false); });
                    {
                        String tipo = "?", estado = "?";
                        try { Dispatch r0 = omnirig.getProperty(nombreRig).toDispatch(); tipo = Dispatch.get(r0, "RigType").getString(); estado = Dispatch.get(r0, "StatusStr").getString(); } catch (Exception ignored) { }
                        cat.registroCat("CAT conectado (conexión " + miSesion + ") a " + nombreRig + " · modelo en OmniRig=" + tipo + " · estado=" + estado);
                    }
                    cat.iniciarLecturaEmisora(rigSeleccionado, miSesion);
                    SwingUtilities.invokeLater(() -> {
                        btnCatToggle.setText("Desconectar CAT");
                        btnCatToggle.setBackground(new Color(200, 0, 0));
                        lblEstado.setText("CAT Conectado a OmniRig (" + nombreRig + ")");
                    });

                    while (catConectado && miSesion == cat.sesionCat) {
                        try {
                            Dispatch rigVivo = omnirig.getProperty(nombreRig).toDispatch();
                            // CONTROL: primero se envían las órdenes pendientes (botones de "Control emisora")
                            cat.procesarOrdenesCat(rigVivo);

                            Variant vFreq = Dispatch.get(rigVivo, "Freq");
                            double freqHz = vFreq.changeType(Variant.VariantDouble).getDouble();
                            cat.ultimaFreqCatHz = Math.round(freqHz);

                            Variant vMode = Dispatch.get(rigVivo, "Mode");
                            int modeInt = vMode.getInt();

                            if (freqHz > 0) {
                                final double fFinal = freqHz;
                                String freqFormateada = String.format(java.util.Locale.GERMANY, "%,.0f", fFinal);
                                String modoStr = convertirModoOmniRig(modeInt);

                                SwingUtilities.invokeLater(() -> {
                                    // Mientras se escribe la frecuencia a mano, no se pisa con lo que dice la emisora
                                    boolean escribiendo = System.currentTimeMillis() - cat.ultimaCifraEscritaMs < 2000
                                            || (cat.envioFrecuenciaEscrita != null && cat.envioFrecuenciaEscrita.isRunning());
                                    if (escribiendo) return;
                                    txtFreqTx.setText(freqFormateada);
                                    txtFreqRx.setText(freqFormateada);
                                    if (!modoStr.isEmpty()) {
                                        cbModo.setSelectedItem(modoStr);
                                    }
                                    detectarBandaPorFrecuencia(fFinal);
                                    comprobarCatContraActividad(bandaPorFrecuencia(fFinal), modoStr);
                                });
                            }
                            // CONTROL: estado para la ventana "Control emisora" (si la emisora lo permite)
                            if (cat.panelControlEmisora != null && cat.panelControlEmisora.isShowing()) {
                                cat.leerEstadoParaControl(rigVivo, freqHz, modeInt);
                            }
                            // Espera de medio segundo, atendiendo las órdenes que lleguen mientras tanto
                            for (int paso = 0; paso < 5 && catConectado; paso++) {
                                Thread.sleep(100);
                                if (!cat.ordenesCat.isEmpty()) cat.procesarOrdenesCat(rigVivo);
                            }
                        } catch (InterruptedException e) {
                            break;
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                    // Seguridad: si se había pulsado SEND, al desconectar la emisora vuelve a recepción
                    if (cat.txPedidoSend || cat.txDigitales) {
                        cat.txPedidoSend = false;
                        cat.txDigitales = false;
                        try { Dispatch.put(omnirig.getProperty(nombreRig).toDispatch(), "Tx", new Variant(cat.PM_RX)); } catch (Throwable ignored) { }
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                    if (automatica) {
                        catConectado = false;
                        SwingUtilities.invokeLater(() -> avisarFalloCatAutomatico("no se pudo abrir OmniRig (" + e.getMessage() + ")"));
                        return;
                    }
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                "No se pudo conectar con OmniRig.\n\n" +
                                        "Asegúrate de que:\n" +
                                        "1. OmniRig esté instalado y ejecutándose.\n" +
                                        "2. El componente COM esté registrado (ejecuta OmniRig como admin una vez).\n" +
                                        "3. Hayas seleccionado un Rig válido en Configuración.\n" +
                                        "4. No tengas LOG4OM u otro programa ocupando el mismo Rig.\n\n" +
                                        "Detalle técnico: " + e.getMessage(),
                                "Error CAT", JOptionPane.ERROR_MESSAGE);
                        btnCatToggle.setText("Conectar CAT");
                        btnCatToggle.setBackground(new Color(0, 150, 0));
                        lblEstado.setText("CAT Desconectado");
                    });
                    catConectado = false;
                } finally {
                    // Se suelta OmniRig: si no, se queda abierto (con el puerto de la emisora ocupado) al cerrar Bitácora 26
                    if (omniSoltar[0] != null) try { omniSoltar[0].safeRelease(); } catch (Throwable ignored) { }
                    ComThread.Release();
                }
            });
            hiloCat.start();
        } else {
            catConectado = false;
            cat.sesionCat++;
            cat.registroCat("CAT desconectado (Rig " + rigSeleccionado + ")");
            if (hiloCat != null) {
                hiloCat.interrupt();
            }
            if (lblAvisoCat != null) lblAvisoCat.setVisible(false);
            avisoCatMostrado = false;
            btnCatToggle.setText("Conectar CAT");
            btnCatToggle.setBackground(new Color(0, 150, 0));
            actualizarEstado();
        }
    }

    /** Botón derecho → "Descargar el diploma de …" en el log de una actividad. */
    private void descargarDiplomaPorId(int id, int numeral) {
        Map<String, String> q = new HashMap<>();
        try (Connection c = DatabaseManager.conectar(); PreparedStatement ps = c.prepareStatement("SELECT COALESCE(indicativo,''), COALESCE(fecha,''), "
                + "COALESCE(hora,''), COALESCE(banda,''), COALESCE(freq_tx,''), COALESCE(modo,''), COALESCE(rst_sent,''), COALESCE(rst_rcvd,''), "
                + "COALESCE(qth,''), COALESCE(nombre,'') FROM qsos WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                q.put("CALL", rs.getString(1).trim().toUpperCase());
                String[] f = rs.getString(2).split("/");
                if (f.length == 3) q.put("QSO_DATE", f[2] + f[1] + f[0]);
                q.put("TIME_ON", rs.getString(3).replace(":", ""));
                q.put("BAND", rs.getString(4));
                if (!rs.getString(5).isBlank()) q.put("FREQ", rs.getString(5).trim());
                q.put("MODE", rs.getString(6));
                q.put("RST_SENT", rs.getString(7));
                q.put("RST_RCVD", rs.getString(8));
                q.put("QTH", rs.getString(9));
                q.put("NAME", rs.getString(10));
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "No se ha podido leer el contacto: " + e.getMessage(), "Descargar diploma", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Object sel = cbSelectorActividad.getSelectedItem();
        String visible = sel == null ? "" : sel.toString();
        int pos = visible.indexOf(" - ");
        GeneradorDiplomaUI.descargar(this, numeral, pos > 0 ? visible.substring(pos + 3) : visible, q);
    }

    /**
     * Al cerrar Bitácora 26 se desconecta el CAT y se suelta OmniRig (espera como mucho 2 s), para que OmniRig
     * se cierre y deje libre el puerto de la emisora.
     */
    private void soltarCatAlCerrar() {
        Thread h = hiloCat;
        catConectado = false;
        if (h == null || !h.isAlive()) return;
        h.interrupt();
        try { h.join(2000); } catch (InterruptedException ignored) { }
    }

    String convertirModoOmniRig(int modeInt) {
        if (modeInt == 8388608) return "CW";
        if (modeInt == 33554432) return "USB";
        if (modeInt == 67108864) return "LSB";
        if (modeInt == 536870912) return "AM";
        if (modeInt == 1073741824) return "FM";

        return switch (modeInt) {
            case 1 -> "SSB";
            case 2 -> "USB";
            case 3 -> "CW";
            case 4 -> "AM";
            case 5 -> "FM";
            default -> "";
        };
    }

    /** Devuelve la banda que corresponde a una frecuencia en Hz ("" si no es ninguna conocida). */
    String bandaPorFrecuencia(double freqHz) {
        double mhz = freqHz / 1000000.0;
        String bandaDetectada = "";
        if (mhz >= 1.8 && mhz <= 2.0) bandaDetectada = "160m";
        else if (mhz >= 3.5 && mhz <= 4.0) bandaDetectada = "80m";
        else if (mhz >= 5.0 && mhz <= 5.5) bandaDetectada = "60m";
        else if (mhz >= 7.0 && mhz <= 7.3) bandaDetectada = "40m";
        else if (mhz >= 10.1 && mhz <= 10.15) bandaDetectada = "30m";
        else if (mhz >= 14.0 && mhz <= 14.35) bandaDetectada = "20m";
        else if (mhz >= 18.068 && mhz <= 18.168) bandaDetectada = "17m";
        else if (mhz >= 21.0 && mhz <= 21.45) bandaDetectada = "15m";
        else if (mhz >= 24.89 && mhz <= 24.99) bandaDetectada = "12m";
        else if (mhz >= 28.0 && mhz <= 29.7) bandaDetectada = "10m";
        else if (mhz >= 26.96 && mhz <= 27.41) bandaDetectada = "CB";
        else if (mhz >= 50.0 && mhz <= 54.0) bandaDetectada = "6m";
        else if (mhz >= 144.0 && mhz <= 148.0) bandaDetectada = "2m";
        else if (mhz >= 430.0 && mhz <= 440.0) bandaDetectada = "70cm";

        return bandaDetectada;
    }

    private void detectarBandaPorFrecuencia(double freqHz) {
        String bandaDetectada = bandaPorFrecuencia(freqHz);
        if (!bandaDetectada.isEmpty() && !bandaDetectada.equals(cbBanda.getSelectedItem())) {
            // La banda la da la emisora: no hay que poner la frecuencia típica de la banda
            cat.bandaPuestaPorFrecuencia = true;
            try { cbBanda.setSelectedItem(bandaDetectada); } finally { cat.bandaPuestaPorFrecuencia = false; }
        }
    }

    private boolean modoCoincide(String requerido, String actual) {
        if (requerido == null || requerido.isEmpty() || actual == null || actual.isEmpty()) return true;
        if (requerido.equalsIgnoreCase(actual)) return true;
        return "SSB".equalsIgnoreCase(requerido)
                && ("USB".equalsIgnoreCase(actual) || "LSB".equalsIgnoreCase(actual));
    }

    /** Avisa si la banda/modo que da la emisora por CAT no coinciden con los de la actividad. */
    private void comprobarCatContraActividad(String bandaCat, String modoCat) {
        if (lblAvisoCat == null) return;

        boolean bandaMal = (!bandaActividadReq.isEmpty() && !bandaActividadReq.equalsIgnoreCase(bandaCat))
                || (!bandasPuntosPermitidas.isEmpty() && !opcionPermitida(bandasPuntosPermitidas, bandaCat, false));
        boolean modoMal = !modoCoincide(modoActividadReq, modoCat)
                || (!modosPuntosPermitidos.isEmpty() && modoCat != null && !modoCat.isEmpty()
                && !opcionPermitida(modosPuntosPermitidos, modoCat, true));

        if (!bandaMal && !modoMal) {
            lblAvisoCat.setVisible(false);
            avisoCatMostrado = false;
            return;
        }

        String enEmisora = ((bandaCat == null || bandaCat.isEmpty()) ? "fuera de banda" : bandaCat)
                + " " + ((modoCat == null || modoCat.isEmpty()) ? "?" : modoCat);
        boolean esPuntos = !bandasPuntosPermitidas.isEmpty() || !modosPuntosPermitidos.isEmpty();
        String enActividad;
        if (esPuntos) {
            enActividad = (bandasPuntosPermitidas.isEmpty() ? "cualquier banda" : String.join("/", bandasPuntosPermitidas))
                    + " " + (modosPuntosPermitidos.isEmpty() ? "cualquier modo" : String.join("/", modosPuntosPermitidos));
        } else {
            enActividad = (bandaActividadReq.isEmpty() ? "cualquier banda" : bandaActividadReq)
                    + " " + (modoActividadReq.isEmpty() ? "cualquier modo" : modoActividadReq);
        }

        lblAvisoCat.setText("¡AVISO! La emisora está en " + enEmisora
                + (esPuntos ? " y la actividad solo admite " : " y la actividad requiere ") + enActividad);
        lblAvisoCat.setVisible(true);

        if (!avisoCatMostrado) {
            avisoCatMostrado = true;
            JOptionPane.showMessageDialog(this,
                    "La banda o el modo de la emisora no coinciden con los de la actividad.\n\n" +
                            "Emisora: " + enEmisora + "\n" +
                            "Actividad: " + enActividad,
                    "Banda o modo distintos", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Comprueba con QRZ.com el usuario y la contraseña: "OK", "MAL" o "RED" (sin conexión). */
    private String comprobarQrz(String usr, String pwd) {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build()) {
            String loginUrl = "https://xmldata.qrz.com/xml/current/?username=" + URLEncoder.encode(usr, StandardCharsets.UTF_8) +
                    ";password=" + URLEncoder.encode(pwd, StandardCharsets.UTF_8) + ";agent=EA4IXR_LogBook";
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(loginUrl))
                    .timeout(java.time.Duration.ofSeconds(10)).header("User-Agent", "EA4IXR-LogBook/1.0").build();
            String xml = client.send(req, HttpResponse.BodyHandlers.ofString()).body();
            return extraerXmlTag(xml, "Key").isEmpty() ? "MAL" : "OK";
        } catch (Exception ex) {
            return "RED";
        }
    }

    // ===================== SUBIDA AUTOMÁTICA A LOS SERVICIOS ONLINE =====================
    private JLabel lblSubida;

    /** Datos de los servicios online que son de cada operador (el usuario y la contraseña de QRZ.com para buscar son comunes). */
    private static final String[] CLAVES_SERVICIO_OPERADOR = {
            "QRZ_API_KEY", "QRZ_FORZAR_INDICATIVO", "QRZ_SUBIR_AUTO",
            "EQSL_USER", "EQSL_PWD", "EQSL_QTH_NICK", "EQSL_SUBIR_AUTO",
            "CLUBLOG_EMAIL", "CLUBLOG_PWD", "CLUBLOG_CALL", "CLUBLOG_SUBIR_AUTO",
            "HRDLOG_USER", "HRDLOG_CODE", "HRDLOG_SUBIR_AUTO", "HRDLOG_ONAIR",
            "HAMQTH_USER", "HAMQTH_PWD", "HAMQTH_CALL", "HAMQTH_SUBIR_AUTO",
            "EQSL_BAJAR", "QRZ_BAJAR", "LOTW_USER", "LOTW_PWD", "LOTW_BAJAR",
            "CLUSTER_URE_CALL", "CLUSTER_URE_PWD", "CLUSTER_DXFUN_CALL", "CLUSTER_DXFUN_PWD"};
    private static final String[] CLAVES_SUBIR_AUTO = {"QRZ_SUBIR_AUTO", "EQSL_SUBIR_AUTO", "CLUBLOG_SUBIR_AUTO", "HRDLOG_SUBIR_AUTO", "HAMQTH_SUBIR_AUTO"};

    private static String claveServicio(String op, String clave) {
        return "SERVICIOS_" + (op == null ? "" : op.trim().toUpperCase()) + "_" + clave;
    }

    /**
     * Antes los servicios online eran uno solo para todos. La primera vez, lo que hubiera configurado
     * pasa a ser del operador que estaba seleccionado (el que lo configuró).
     */
    private static synchronized void pasarServiciosAOperador() {
        if ("1".equals(ConfigApp.get("SERVICIOS_POR_OPERADOR", "0"))) return;
        String op = ConfigApp.get("OPERADOR_ACTUAL", "").trim().toUpperCase();
        if (!op.isEmpty()) {
            for (String k : CLAVES_SERVICIO_OPERADOR) {
                String v = ConfigApp.get(k, "");
                if (v != null && !v.isEmpty() && ConfigApp.get(claveServicio(op, k), "").isEmpty())
                    ConfigApp.put(claveServicio(op, k), v);
            }
        }
        ConfigApp.put("SERVICIOS_POR_OPERADOR", "1");
    }

    /** Un dato de los servicios online del operador que está usando el programa (para los clusters). */
    static String datoServicioActual(String clave) {
        return servicio(operadorActual == null ? "" : operadorActual.trim().toUpperCase(), clave, "");
    }

    /** Para ConfirmacionesOnline: un dato de los servicios de un operador. */
    static String datoServicio(String op, String clave) { return servicio(op, clave, ""); }

    /** Un dato de los servicios online de un operador concreto. */
    private static String servicio(String op, String clave, String def) {
        pasarServiciosAOperador();
        String v = ConfigApp.get(claveServicio(op, clave), "");
        return v == null || v.isEmpty() ? def : v;
    }

    /** Operador que hizo el contacto (sus servicios son los que se usan para subirlo). */
    private static String operadorDe(String[] q) {
        String op = q[12] == null ? "" : q[12].trim().toUpperCase();
        return op.isEmpty() ? (operadorActual == null ? "" : operadorActual.trim().toUpperCase()) : op;
    }

    private static boolean algunServicioActivo(String op) {
        for (String k : CLAVES_SUBIR_AUTO) if ("1".equals(servicio(op, k, "0"))) return true;
        return false;
    }

    /** Sube el QSO recién guardado a cada servicio que tenga marcada "Subir automáticamente cada QSO nuevo". */
    private void subirQsoAutomatico(String uuid) {
        if (uuid == null) return;
        Thread t = new Thread(() -> subirQsoAhora(uuid), "SubirQSO");
        t.setDaemon(true);
        t.start();
    }

    /** Sube un contacto a los servicios del operador que lo hizo. null = no tocaba subirlo; true = bien; false = algún servicio falló. */
    private Boolean subirQsoAhora(String uuid) {
        {
            String[] q = leerQsoParaSubir(uuid);
            if (q == null) return null;
            // Se usan los servicios del operador que hizo el contacto
            if (!algunServicioActivo(operadorDe(q))) return null;
            // En CB no hay logbook online (y QRZ.com no se usa en CB)
            if ("CB".equalsIgnoreCase(q[3])) return null;
            mostrarSubida("Subiendo " + q[0] + " a los servicios online…", TEMA_TEXTO, null);
            List<String> bien = new ArrayList<>(), mal = new ArrayList<>();
            String opq = operadorDe(q);
            subirA(opq, "QRZ_SUBIR_AUTO", "QRZ.com", () -> subirQrz(q), bien, mal);
            subirA(opq, "EQSL_SUBIR_AUTO", "eQSL", () -> subirEqsl(q), bien, mal);
            subirA(opq, "CLUBLOG_SUBIR_AUTO", "Club Log", () -> subirClubLog(q), bien, mal);
            subirA(opq, "HRDLOG_SUBIR_AUTO", "HRDLog", () -> subirHrdLog(q), bien, mal);
            subirA(opq, "HAMQTH_SUBIR_AUTO", "HamQTH", () -> subirHamQth(q), bien, mal);
            if (mal.isEmpty()) {
                try (Connection conn = DatabaseManager.conectar();
                     PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET subido = 1 WHERE uuid = ?")) {
                    ps.setString(1, uuid);
                    ps.executeUpdate();
                } catch (Exception ignored) { }
            }
            ultimaSubidaUuid = uuid;
            ultimoDetalleSubida = new ArrayList<>();
            for (String b : bien) ultimoDetalleSubida.add(b + ": subido correctamente");
            ultimoDetalleSubida.addAll(mal);
            StringBuilder txt = new StringBuilder(q[0] + (opq.isEmpty() ? "" : " (cuentas de " + opq + ")") + ": ");
            for (String b : bien) txt.append(b).append(" ✓   ");
            for (String m : mal) txt.append(m.substring(0, m.indexOf(':'))).append(" ✗   ");
            String detalle = mal.isEmpty() ? null : "<html>" + String.join("<br>", mal) + "</html>";
            if (!mal.isEmpty()) txt.append(" (pincha aquí para ver el motivo)");
            mostrarSubida(txt.toString().trim(), mal.isEmpty() ? cat.VERDE_ENCENDIDO : new Color(255, 110, 90), detalle);
            return mal.isEmpty();
        }
    }

    /**
     * Botón "Actualizar contactos" (segunda parte): sube a los servicios online los contactos de este log que aún no se han subido.
     * Solo los que no son de CB y cuyo operador tiene en este ordenador algún servicio con "Subir automáticamente".
     * aviso: texto que va delante en la pregunta (o null). Si no hay nada que subir: con aviso se muestra, sin aviso no sale nada.
     */
    private void subirContactosSinSubir(int numeral, boolean vistaRC, String aviso) {
        String filtro = numeral > 0 ? "actividad_numeral = ?" : vistaRC ? "radioclub = 1" : "operador = ?";
        List<String[]> sinSubir = new ArrayList<>();   // {uuid, id}
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(uuid,''), id, COALESCE(operador,'') FROM qsos WHERE " + filtro
                     + " AND COALESCE(subido,0) = 0 AND UPPER(COALESCE(banda,'')) <> 'CB' ORDER BY id")) {
            if (numeral > 0) ps.setInt(1, numeral);
            else if (!vistaRC) ps.setString(1, operadorActual == null ? "" : operadorActual);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String op = rs.getString(3).trim().toUpperCase();
                    if (op.isEmpty()) op = operadorActual == null ? "" : operadorActual.trim().toUpperCase();
                    if (algunServicioActivo(op)) sinSubir.add(new String[]{rs.getString(1), String.valueOf(rs.getInt(2))});
                }
            }
        } catch (Exception ex) {
            return;
        }
        if (sinSubir.isEmpty()) {
            if (aviso != null) JOptionPane.showMessageDialog(this, aviso + "\nY no queda ningún contacto por subir a tus servicios online.\nNo falta nada.",
                    "Actualizar contactos", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object[] botones = {"Subirlos ahora", "Ya están subidos", "Ahora no"};
        int r = JOptionPane.showOptionDialog(this, (aviso == null ? "" : aviso + "\n\n")
                        + sinSubir.size() + " contactos de este log no se han subido todavía a tus servicios online\n"
                        + "(los que tienes marcados en Mi Estación → Servicios online).\n\n"
                        + "• \"Subirlos ahora\": se suben uno a uno. Si alguno ya estaba en el servicio, no se duplica.\n"
                        + "• \"Ya están subidos\": si los subiste con otro programa; se apuntan como subidos sin enviarlos.\n"
                        + "• \"Ahora no\": no se hace nada.",
                "Actualizar contactos", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, botones, botones[0]);
        if (r == 1) {
            try (Connection conn = DatabaseManager.conectar(); PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET subido = 1 WHERE id = ?")) {
                for (String[] q : sinSubir) { ps.setInt(1, Integer.parseInt(q[1])); ps.executeUpdate(); }
            } catch (Exception ignored) { }
            JOptionPane.showMessageDialog(this, sinSubir.size() + " contactos apuntados como ya subidos.", "Actualizar contactos", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (r != 0) return;

        JDialog dlg = new JDialog(this, "Subir contactos", Dialog.ModalityType.APPLICATION_MODAL);
        JProgressBar barra = new JProgressBar(0, sinSubir.size());
        barra.setStringPainted(true);
        JLabel lbl = new JLabel("Empezando…");
        JButton bParar = new JButton("Parar");
        final boolean[] parar = {false};
        bParar.addActionListener(e -> { parar[0] = true; bParar.setEnabled(false); lbl.setText("Parando…"); });
        JPanel pp = new JPanel(new BorderLayout(8, 8));
        pp.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        pp.add(lbl, BorderLayout.NORTH);
        pp.add(barra, BorderLayout.CENTER);
        JPanel abajo = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        abajo.add(bParar);
        pp.add(abajo, BorderLayout.SOUTH);
        dlg.setContentPane(pp);
        dlg.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dlg.setSize(460, 150);
        dlg.setLocationRelativeTo(this);
        int[] cuenta = new int[3];   // 0 bien, 1 con fallos, 2 revisados
        Thread hilo = new Thread(() -> {
            try {
                for (int k = 0; k < sinSubir.size() && !parar[0]; k++) {
                    String[] q = sinSubir.get(k);
                    final int paso = k;
                    SwingUtilities.invokeLater(() -> { barra.setValue(paso); if (!parar[0]) lbl.setText("Subiendo contacto " + (paso + 1) + " de " + sinSubir.size() + "…"); });
                    String uuid = q[0];
                    if (uuid.isEmpty()) {   // contactos antiguos sin identificador: se le pone uno
                        uuid = java.util.UUID.randomUUID().toString();
                        try (Connection conn = DatabaseManager.conectar(); PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET uuid = ? WHERE id = ?")) {
                            ps.setString(1, uuid); ps.setInt(2, Integer.parseInt(q[1])); ps.executeUpdate();
                        } catch (Exception ignored) { }
                    }
                    Boolean ok = subirQsoAhora(uuid);
                    cuenta[2]++;
                    if (Boolean.TRUE.equals(ok)) cuenta[0]++; else if (Boolean.FALSE.equals(ok)) cuenta[1]++;
                    try { Thread.sleep(300); } catch (InterruptedException ie) { break; }
                }
            } finally {
                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    String msg = "Contactos subidos: " + cuenta[0] + " de " + sinSubir.size() + ".";
                    if (cuenta[1] > 0) msg += "\n" + cuenta[1] + " no se han podido subir a algún servicio: se volverán a ofrecer la próxima vez.\n"
                            + "(Pincha el aviso de abajo para ver el motivo del último.)";
                    if (parar[0]) msg += "\n\n(Has parado la subida antes de terminar.)";
                    JOptionPane.showMessageDialog(LibroGuardiaUI.this, msg, "Subir contactos", JOptionPane.INFORMATION_MESSAGE);
                });
            }
        }, "SubirSinSubir");
        hilo.setDaemon(true);
        hilo.start();
        dlg.setVisible(true);
    }

    /** Botón derecho → "Subir a los servicios online" sobre un contacto del log. */
    private void subirQsoPorId(int id) {
        String uuid = null, banda = "", opContacto = "";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement("SELECT uuid, banda, operador FROM qsos WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { uuid = rs.getString(1); banda = rs.getString(2); opContacto = rs.getString(3); } }
        } catch (Exception ignored) { }
        String opq = operadorDe(new String[]{"", "", "", "", "", "", "", "", "", "", "", "", opContacto == null ? "" : opContacto, ""});
        if (!algunServicioActivo(opq)) {
            JOptionPane.showMessageDialog(this, "El operador " + opq + " (el que hizo este contacto) no tiene ningún servicio con la casilla\n"
                    + "\"Subir automáticamente cada QSO nuevo\".\n"
                    + "Márcala en Mi Estación → Servicios online, eligiendo arriba ese operador.", "Subir contacto", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if ("CB".equalsIgnoreCase(banda)) {
            JOptionPane.showMessageDialog(this, "Los contactos de CB no se suben a los servicios online.", "Subir contacto", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (uuid == null || uuid.isEmpty()) {
            // Contactos antiguos sin identificador: se le pone uno
            uuid = java.util.UUID.randomUUID().toString();
            try (Connection conn = DatabaseManager.conectar();
                 PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET uuid = ? WHERE id = ?")) {
                ps.setString(1, uuid); ps.setInt(2, id); ps.executeUpdate();
            } catch (Exception ignored) { }
        }
        subirQsoAutomatico(uuid);
    }

    private volatile String ultimaSubidaUuid;
    private volatile List<String> ultimoDetalleSubida;

    /** Al pinchar en el aviso de abajo: qué ha pasado con cada servicio, y botón para reintentarlo. */
    private void verDetalleSubida() {
        List<String> det = ultimoDetalleSubida;
        if (det == null || ultimaSubidaUuid == null) return;
        JTextArea t = new JTextArea(String.join("\n\n", det), Math.min(14, det.size() * 2 + 1), 70);
        t.setEditable(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        Object[] botones = {"Reintentar", "Cerrar"};
        int r = JOptionPane.showOptionDialog(this, new JScrollPane(t), "Subida a los servicios online",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, botones, botones[1]);
        if (r == 0) subirQsoAutomatico(ultimaSubidaUuid);
    }

    private void subirA(String op, String clave, String nombre, java.util.concurrent.Callable<String> subida, List<String> bien, List<String> mal) {
        if (!"1".equals(servicio(op, clave, "0"))) return;
        String error;
        try { error = subida.call(); } catch (Exception ex) {
            error = "no se ha podido conectar (" + ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage()) + ")";
        }
        if (error == null) bien.add(nombre); else mal.add(nombre + ": " + error);
    }

    private void mostrarSubida(String texto, Color color, String detalle) {
        SwingUtilities.invokeLater(() -> {
            if (lblSubida == null) return;
            lblSubida.setForeground(color);
            lblSubida.setText(texto);
            lblSubida.setToolTipText(detalle);
        });
    }

    /** {indicativo, fecha, hora, banda, modo, freqTx, freqRx, rstS, rstR, nombre, qth, grid, operador, estacion} */
    private String[] leerQsoParaSubir(String uuid) {
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement("SELECT indicativo, fecha, hora, banda, modo, freq_tx, freq_rx, "
                     + "rst_sent, rst_rcvd, nombre, qth, grid, operador, estacion FROM qsos WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String[] q = new String[14];
                for (int i = 0; i < 14; i++) q[i] = rs.getString(i + 1) == null ? "" : rs.getString(i + 1).trim();
                return q;
            }
        } catch (Exception ex) {
            return null;
        }
    }

    /** Indicativo de la estación: el del radioclub si el contacto es del radioclub; si no, el del operador. */
    private static String estacionDe(String[] q) { return q[13].isEmpty() ? q[12] : q[13]; }

    /** Un contacto en formato ADIF (sin tildes, que algunos servicios no las admiten). */
    private String registroAdif(String[] q, String estacion, String extra) {
        StringBuilder sb = new StringBuilder();
        java.util.function.BiConsumer<String, String> campo = (n, v) -> {
            if (v == null) return;
            v = sinTildes(v).trim();
            if (v.isEmpty()) return;
            sb.append('<').append(n).append(':').append(v.length()).append('>').append(v).append(' ');
        };
        campo.accept("CALL", q[0]);
        String[] f = q[1].split("/");
        if (f.length == 3) campo.accept("QSO_DATE", f[2] + f[1] + f[0]);
        String h = q[2].replace(":", "");
        if (h.length() == 4) h += "00";
        campo.accept("TIME_ON", h);
        campo.accept("BAND", q[3].toLowerCase());
        String modo = q[4].toUpperCase();
        if (modo.equals("LSB") || modo.equals("USB")) { campo.accept("MODE", "SSB"); campo.accept("SUBMODE", modo); }
        else if (modo.equals("FT4")) { campo.accept("MODE", "MFSK"); campo.accept("SUBMODE", "FT4"); }   // así lo pide el formato ADIF
        else campo.accept("MODE", modo);
        campo.accept("FREQ", q[5]);
        if (!q[6].isEmpty() && !q[6].equals(q[5])) campo.accept("FREQ_RX", q[6]);
        campo.accept("RST_SENT", q[7]);
        campo.accept("RST_RCVD", q[8]);
        campo.accept("NAME", q[9]);
        campo.accept("QTH", q[10]);
        campo.accept("GRIDSQUARE", q[11]);
        campo.accept("STATION_CALLSIGN", estacion);
        if (!q[12].isEmpty() && !q[12].equals(estacion)) campo.accept("OPERATOR", q[12]);
        String miGrid = q[13].isEmpty() ? leerDatosOperador(q[12])[4] : ConfigApp.get("RADIOCLUB_GRID", "");
        campo.accept("MY_GRIDSQUARE", miGrid);
        if (extra != null) sb.append(extra);
        sb.append("<EOR>");
        return sb.toString();
    }

    private static String formulario(String... pares) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < pares.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(pares[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(pares[i + 1] == null ? "" : pares[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Botón "Probar" de Servicios Online: comprueba con el servicio los datos escritos, sin subir ningún contacto.
     * Devuelve {"OK" | "MAL" | "AVISO", explicación en palabras sencillas}.
     */
    private String[] probarServicioOnline(String servicio, Map<String, String> d) {
        try {
            switch (servicio.toUpperCase()) {
                case "QRZ.COM": {
                    String usr = d.getOrDefault("QRZ_USER", ""), pwd = d.getOrDefault("QRZ_PWD", ""), key = d.getOrDefault("QRZ_API_KEY", "");
                    if (usr.isEmpty() && key.isEmpty()) return new String[]{"MAL", "escribe el usuario y la contraseña, o la API Key."};
                    StringBuilder r = new StringBuilder();
                    boolean bien = true;
                    if (!usr.isEmpty()) {
                        String c = pwd.isEmpty() ? "FALTA" : comprobarQrz(usr, pwd);
                        if (c.equals("OK")) r.append("usuario y contraseña correctos");
                        else { bien = false; r.append(c.equals("RED") ? "no se puede conectar (¿hay internet?)" : c.equals("FALTA") ? "falta la contraseña" : "el usuario o la contraseña no coinciden"); }
                    }
                    if (!key.isEmpty()) {
                        String[] k = enviarFormulario("https://logbook.qrz.com/api", formulario("KEY", key, "ACTION", "STATUS"));
                        String b = k[1] == null ? "" : k[1];
                        if (r.length() > 0) r.append(" · ");
                        if (b.contains("RESULT=OK")) r.append("API Key correcta");
                        else { bien = false; r.append("la API Key no vale (QRZ.com → Logbook → Settings)"); }
                    }
                    return new String[]{bien ? "OK" : "MAL", r.toString() + "."};
                }
                case "EQSL": {
                    String usr = d.getOrDefault("EQSL_USER", ""), pwd = d.getOrDefault("EQSL_PWD", ""), nick = d.getOrDefault("EQSL_QTH_NICK", "");
                    if (usr.isEmpty() || pwd.isEmpty()) return new String[]{"MAL", "escribe el usuario y la contraseña."};
                    String url = "https://www.eqsl.cc/qslcard/DownloadInBox.cfm?UserName=" + URLEncoder.encode(usr, StandardCharsets.UTF_8)
                            + "&Password=" + URLEncoder.encode(pwd, StandardCharsets.UTF_8) + "&RcvdSince=29991231"
                            + (nick.isEmpty() ? "" : "&QTHNickname=" + URLEncoder.encode(nick, StandardCharsets.UTF_8));
                    try (HttpClient c = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()) {
                        String b = c.send(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(20))
                                .header("User-Agent", "Bitacora26-EA4IXR/1.0").build(), HttpResponse.BodyHandlers.ofString()).body();
                        String t = recorta(b), l = t.toLowerCase();
                        if (l.contains("no such username") || l.contains("password")) return new String[]{"MAL", "el usuario o la contraseña no coinciden."};
                        if (l.contains("qth nickname") || l.contains("nickname")) return new String[]{"MAL", "el QTH Nickname no coincide con ninguno de tu cuenta."};
                        if (l.contains("error")) return new String[]{"MAL", t};
                        return new String[]{"OK", "usuario y contraseña correctos."};
                    }
                }
                case "LOTW": {
                    String r = ConfirmacionesOnline.probarLotw(d.getOrDefault("LOTW_USER", ""), d.getOrDefault("LOTW_PWD", ""));
                    return r == null ? new String[]{"OK", "usuario y contraseña correctos."} : new String[]{"MAL", r};
                }
                case "CLUSTER URE":
                case "CLUSTER DXFUN": {
                    boolean ure = servicio.equalsIgnoreCase("Cluster URE");
                    String call = d.getOrDefault(ure ? "CLUSTER_URE_CALL" : "CLUSTER_DXFUN_CALL", "").trim().toUpperCase();
                    if (call.isEmpty()) call = operadorActual == null ? "" : operadorActual.trim().toUpperCase();
                    String pwd = d.getOrDefault(ure ? "CLUSTER_URE_PWD" : "CLUSTER_DXFUN_PWD", "");
                    for (ClustersDX.Fuente f : ClustersDX.FUENTES)
                        if (f.id().equals(ure ? "URE" : "DXFUN")) return ClustersDX.probarEntrada(f, call, pwd);
                    return new String[]{"MAL", "cluster desconocido."};
                }
                case "HAMQTH": {
                    String usr = d.getOrDefault("HAMQTH_USER", ""), pwd = d.getOrDefault("HAMQTH_PWD", "");
                    if (usr.isEmpty() || pwd.isEmpty()) return new String[]{"MAL", "escribe el usuario y la contraseña."};
                    String url = "https://www.hamqth.com/xml.php?u=" + URLEncoder.encode(usr, StandardCharsets.UTF_8)
                            + "&p=" + URLEncoder.encode(pwd, StandardCharsets.UTF_8);
                    try (HttpClient c = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build()) {
                        String b = c.send(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(20))
                                .header("User-Agent", "Bitacora26").build(), HttpResponse.BodyHandlers.ofString()).body();
                        if (!extraerXmlTag(b, "session_id").isEmpty()) {
                            String aviso = d.getOrDefault("HAMQTH_CALL", "").isEmpty() ? " (pon también tu indicativo en «Indicativo»)" : "";
                            return new String[]{"OK", "usuario y contraseña correctos" + aviso + "."};
                        }
                        String err = extraerXmlTag(b, "error");
                        return new String[]{"MAL", err.toLowerCase().contains("wrong") ? "el usuario o la contraseña no coinciden (compruébalos entrando en hamqth.com)." : (err.isEmpty() ? "respuesta no esperada." : err)};
                    }
                }
                case "CLUB LOG":
                    return new String[]{"AVISO", "Club Log no permite comprobar la cuenta sin subir un contacto: se verá al subir el primero (abajo sale ✓ o ✗)."};
                case "HRDLOG":
                    return new String[]{"AVISO", "HRDLog no permite comprobar el código sin subir un contacto: se verá al subir el primero (abajo sale ✓ o ✗)."};
                default:
                    return new String[]{"AVISO", "no se puede probar este servicio."};
            }
        } catch (Exception ex) {
            return new String[]{"MAL", "no se puede conectar (¿hay internet?)."};
        }
    }

    /** Envía un formulario (POST) y devuelve {código HTTP, respuesta}. */
    private static String[] enviarFormulario(String url, String cuerpo) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build()) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "Bitacora26-EA4IXR/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build();
            HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
            return new String[]{String.valueOf(r.statusCode()), r.body() == null ? "" : r.body()};
        }
    }

    private static String recorta(String t) {
        t = t == null ? "" : t.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }

    /** QRZ.com Logbook. Devuelve null si ha ido bien, o el motivo del fallo. */
    private String subirQrz(String[] q) throws Exception {
        String key = servicio(operadorDe(q), "QRZ_API_KEY", "").trim();
        if (key.isEmpty()) return "falta la API Key";
        String forzado = servicio(operadorDe(q), "QRZ_FORZAR_INDICATIVO", "").trim();
        String est = forzado.isEmpty() ? estacionDe(q) : forzado;
        String[] r = enviarFormulario("https://logbook.qrz.com/api",
                formulario("KEY", key, "ACTION", "INSERT", "ADIF", registroAdif(q, est, null)));
        String b = r[1];
        if (b.contains("RESULT=OK") || b.contains("RESULT=REPLACE")) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("REASON=([^&]*)").matcher(b);
        String motivo = m.find() ? java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : recorta(b);
        if (motivo.toLowerCase().contains("duplicate")) return null;   // ya estaba subido
        return motivo;
    }

    /** eQSL.cc */
    private String subirEqsl(String[] q) throws Exception {
        String usr = servicio(operadorDe(q), "EQSL_USER", "").trim(), pwd = servicio(operadorDe(q), "EQSL_PWD", "");
        if (usr.isEmpty() || pwd.isEmpty()) return "faltan el usuario o la contraseña";
        String nick = servicio(operadorDe(q), "EQSL_QTH_NICK", "").trim();
        String extra = "<EQSL_USER:" + usr.length() + ">" + usr + " <EQSL_PSWD:" + pwd.length() + ">" + pwd + " "
                + (nick.isEmpty() ? "" : "<APP_EQSL_QTH_NICKNAME:" + nick.length() + ">" + nick + " ");
        String adif = "Bitacora 26 <ADIF_VER:5>3.1.4 <EOH> " + registroAdif(q, usr, extra);
        String[] r = enviarFormulario("https://www.eqsl.cc/qslcard/importADIF.cfm", formulario("ADIFData", adif));
        String b = r[1];
        if (b.contains("1 out of 1 records added")) return null;
        if (b.toLowerCase().contains("duplicate")) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(Error:[^<]*|Warning:[^<]*)").matcher(b);
        return m.find() ? recorta(m.group(1)) : recorta(b);
    }

    /**
     * Club Log solo acepta subidas de programas registrados, con la clave de PROGRAMA de Bitácora 26 (no la del usuario).
     * Esa clave ya no va dentro de la aplicación (se podría sacar del programa): está solo en el servidor de
     * bitacora26.es, que hace de pasarela y la añade a cada envío.
     */
    static final String PASARELA_CLUBLOG = "https://bitacora26.es/api/clublog";

    /** Club Log (subida en tiempo real) */
    private String subirClubLog(String[] q) throws Exception {
        String mail = servicio(operadorDe(q), "CLUBLOG_EMAIL", "").trim(), pwd = servicio(operadorDe(q), "CLUBLOG_PWD", "");
        String call = servicio(operadorDe(q), "CLUBLOG_CALL", "").trim();
        if (mail.isEmpty() || pwd.isEmpty()) return "faltan el e-mail o la contraseña";
        if (call.isEmpty()) call = estacionDe(q);
        // Se envía a la pasarela de bitacora26.es, que añade la clave del programa y lo entrega a Club Log
        // (el correo y la contraseña solo se usan para este envío: el servidor no los guarda)
        String cuerpo = formulario("email", mail, "password", pwd, "callsign", call, "adif", registroAdif(q, call, null));
        String[] r = enviarFormulario(PASARELA_CLUBLOG, cuerpo);
        if (r[0].equals("503") || r[0].equals("502")) return "no se puede llegar a Club Log a través de bitacora26.es ahora mismo (pulsa Reintentar más tarde)";
        if (r[0].equals("200")) return null;   // "OK", "Dupe" o "Updated QSO"
        if (r[0].equals("403") || r[1].startsWith("Login rejected")) {
            // Club Log pide no insistir con datos rechazados: se desactiva la subida hasta que se revisen
            ConfigApp.put(claveServicio(operadorDe(q), "CLUBLOG_SUBIR_AUTO"), "0");
            return "Club Log ha rechazado el acceso (" + r[0] + "). Se ha desactivado la subida a Club Log: revisa el e-mail "
                    + "y la contraseña de " + operadorDe(q) + " en Mi Estación → Servicios online y vuelve a marcar la casilla.";
        }
        return recorta(r[1]);   // otros errores: se avisa y no se reintenta solo
    }

    /** HRDLog.net */
    private String subirHrdLog(String[] q) throws Exception {
        String usr = servicio(operadorDe(q), "HRDLOG_USER", "").trim(), code = servicio(operadorDe(q), "HRDLOG_CODE", "").trim();
        if (usr.isEmpty() || code.isEmpty()) return "faltan el usuario o el código de subida";
        String[] r = enviarFormulario("https://robot.hrdlog.net/NewEntry.aspx",
                formulario("Callsign", usr, "Code", code, "App", "Bitacora 26", "ADIFData", registroAdif(q, usr, null)));
        String b = r[1];
        if (b.contains("<insert>1</insert>")) return null;
        if (b.contains("<insert>0")) return null;   // HRDLog: ya estaba subido (duplicado)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<error>(.*?)</error>", java.util.regex.Pattern.DOTALL).matcher(b);
        if (m.find()) {
            String e = recorta(m.group(1));
            return e.toLowerCase().contains("duplicat") ? null : e;
        }
        return recorta(b);
    }

    /** HamQTH */
    private String subirHamQth(String[] q) throws Exception {
        // Sin espacios al principio ni al final (al pegar la contraseña a veces se cuela uno y HamQTH la rechaza)
        String usr = servicio(operadorDe(q), "HAMQTH_USER", "").trim(), pwd = servicio(operadorDe(q), "HAMQTH_PWD", "").trim();
        String call = servicio(operadorDe(q), "HAMQTH_CALL", "").trim();
        if (usr.isEmpty() || pwd.isEmpty()) return "faltan el usuario o la contraseña";
        if (call.isEmpty()) call = estacionDe(q);
        String[] r = enviarFormulario("https://www.hamqth.com/qso_realtime.php",
                formulario("u", usr, "p", pwd, "c", call, "prg", "Bitacora26", "cmd", "insert", "adif", registroAdif(q, call, null)));
        if (r[0].equals("200")) return null;   // "QSO inserted"
        String b = recorta(r[1]);
        return b.toLowerCase().contains("already") || b.toLowerCase().contains("duplicate") ? null : b;
    }

    /** Mi Estación → Servicios online: una pestaña por servicio (QRZ, eQSL, Club Log, HRDLog, HamQTH). */
    private void abrirDialogoServiciosOnline() {
        JDialog dialogo = new JDialog(this, "Servicios online", true);
        dialogo.setLayout(new BorderLayout(8, 8));
        // Una lista de servicios con su estado; «Configurar» enseña solo lo de ese servicio (tarjeta)
        CardLayout cartas = new CardLayout();
        JPanel tarjetas = new JPanel(cartas);
        final String[] servVisible = {""};   // "" = se ve la lista
        Map<String, JComponent> campos = new LinkedHashMap<>();   // clave de configuración -> campo
        java.util.Set<String> deOperador = new java.util.HashSet<>(java.util.Arrays.asList(CLAVES_SERVICIO_OPERADOR));
        pasarServiciosAOperador();
        // Cada operador tiene sus propias cuentas: se elige arriba de quién son los datos que se ven
        List<String> ops = obtenerListaOperadores();
        String opInicial = operadorActual == null ? "" : operadorActual.trim().toUpperCase();
        if (!opInicial.isEmpty() && !ops.contains(opInicial)) ops.add(0, opInicial);
        JComboBox<String> comboOp = new JComboBox<>(ops.toArray(new String[0]));
        comboOp.setSelectedItem(opInicial);
        Map<String, Map<String, String>> borrador = new LinkedHashMap<>();   // operador -> sus datos (aún sin guardar)
        java.util.function.Function<String, Map<String, String>> datosDe = op -> borrador.computeIfAbsent(op, o -> {
            Map<String, String> m = new LinkedHashMap<>();
            for (String k : CLAVES_SERVICIO_OPERADOR) m.put(k, servicio(o, k, ""));
            return m;
        });
        java.util.function.Function<String, String> valorInicial = clave ->
                deOperador.contains(clave) ? datosDe.apply(opInicial).getOrDefault(clave, "") : ConfigApp.get(clave, "");

        // Ayudante para montar cada pestaña: {etiqueta, clave, tipo (T texto, P contraseña, C casilla)} + nota
        java.util.function.BiFunction<String[][], String, JPanel> pestana = (filas, nota) -> {
            JPanel pnl = new JPanel(new GridBagLayout());
            pnl.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(5, 5, 5, 5);
            g.anchor = GridBagConstraints.WEST;
            g.fill = GridBagConstraints.HORIZONTAL;
            int y = 0;
            JPanel avanzadas = new JPanel(new GridBagLayout());
            avanzadas.setOpaque(false);
            GridBagConstraints ga = new GridBagConstraints();
            ga.insets = new Insets(5, 5, 5, 5);
            ga.anchor = GridBagConstraints.WEST;
            ga.fill = GridBagConstraints.HORIZONTAL;
            int ya = 0;
            for (String[] f0 : filas) {
                boolean avanzada = f0[0].startsWith("+");
                String[] f = f0.clone();
                if (avanzada) f[0] = f[0].substring(1);
                if (avanzada) {
                    JTextField t = "P".equals(f[2]) ? new JPasswordField(valorInicial.apply(f[1]), 24) : new JTextField(valorInicial.apply(f[1]), 24);
                    if (f.length > 3 && "MAY".equals(f[3])) aplicarFiltroMayusculas(t);
                    ga.gridy = ya++;
                    ga.gridx = 0; ga.weightx = 0;
                    avanzadas.add(new JLabel(f[0]), ga);
                    ga.gridx = 1; ga.weightx = 1;
                    avanzadas.add(t, ga);
                    campos.put(f[1], t);
                    continue;
                }
                g.gridy = y++;
                JComponent campo;
                if ("C".equals(f[2])) {
                    JCheckBox chk = new JCheckBox(f[0], "1".equals(valorInicial.apply(f[1])));
                    g.gridx = 0; g.gridwidth = 2;
                    pnl.add(chk, g);
                    g.gridwidth = 1;
                    campo = chk;
                } else {
                    JTextField t = "P".equals(f[2]) ? new JPasswordField(valorInicial.apply(f[1]), 24) : new JTextField(valorInicial.apply(f[1]), 24);
                    if (f.length > 3 && "MAY".equals(f[3])) aplicarFiltroMayusculas(t);
                    g.gridx = 0; g.weightx = 0;
                    pnl.add(new JLabel(f[0]), g);
                    g.gridx = 1; g.weightx = 1;
                    pnl.add(t, g);
                    campo = t;
                }
                campos.put(f[1], campo);
            }
            if (avanzadas.getComponentCount() > 0) {
                JButton bMas = new JButton("▸ Más opciones");
                bMas.setFont(new Font("Dialog", Font.PLAIN, 12));   // "Dialog" tiene las flechas
                avanzadas.setVisible(false);
                bMas.addActionListener(ev -> {
                    avanzadas.setVisible(!avanzadas.isVisible());
                    bMas.setText(avanzadas.isVisible() ? "▾ Menos opciones" : "▸ Más opciones");
                    pnl.revalidate();
                });
                g.gridy = y++; g.gridx = 0; g.gridwidth = 2; g.fill = GridBagConstraints.NONE;
                g.insets = new Insets(10, 5, 2, 5);
                pnl.add(bMas, g);
                g.gridy = y++; g.fill = GridBagConstraints.HORIZONTAL;
                g.insets = new Insets(0, 0, 0, 0);
                pnl.add(avanzadas, g);
                g.gridwidth = 1;
                g.insets = new Insets(5, 5, 5, 5);
            }
            if (nota != null) {
                JLabel lbl = new JLabel("<html><div style='width:380px'>" + nota + "</div></html>");
                lbl.setFont(new Font("Arial", Font.ITALIC, 11));
                g.gridy = y++; g.gridx = 0; g.gridwidth = 2;
                g.insets = new Insets(12, 5, 5, 5);
                pnl.add(lbl, g);
            }
            g.gridy = y; g.weighty = 1;
            pnl.add(Box.createVerticalGlue(), g);
            return pnl;
        };

        java.util.function.BiConsumer<String, JPanel> anadirServicio = (nombre, pnl) -> tarjetas.add(pnl, nombre);
        anadirServicio.accept("QRZ.COM", pestana.apply(new String[][]{
                {"Nombre de usuario:", "QRZ_USER", "T"},
                {"Contraseña:", "QRZ_PWD", "P"},
                {"API Key (logbook):", "QRZ_API_KEY", "T", "MAY"},
                {"Subir cada contacto", "QRZ_SUBIR_AUTO", "C"},
                {"Bajar mis confirmaciones (pone la ★)", "QRZ_BAJAR", "C"},
                {"+Forzar indicativo de estación:", "QRZ_FORZAR_INDICATIVO", "T", "MAY"}},
                "El usuario y la contraseña sirven para buscar indicativos (se comprueban al guardar). "
                        + "La <b>API Key</b> es para subir los contactos a tu logbook de QRZ.com: está en QRZ.com → Logbook → Settings. "
                        + "<b>Ojo:</b> para subir el log desde otros programas QRZ.com pide tener una suscripción de pago; sin ella, QRZ.com rechaza la subida. "
                        + "En «Más opciones», «Forzar indicativo»: QRZ.com rechaza los contactos con un indicativo distinto del registrado (por ejemplo con /P); "
                        + "si pones ahí tu indicativo, se subirán con él."));
        anadirServicio.accept("eQSL", pestana.apply(new String[][]{
                {"Usuario:", "EQSL_USER", "T", "MAY"},
                {"Contraseña:", "EQSL_PWD", "P"},
                {"Subir cada contacto", "EQSL_SUBIR_AUTO", "C"},
                {"Bajar las QSL que me llegan (con su imagen)", "EQSL_BAJAR", "C"},
                {"+QTH Nickname:", "EQSL_QTH_NICK", "T"}},
                "El QTH Nickname es el nombre que le diste a tu ubicación en eQSL.cc (si tienes varias). "
                        + "Las QSL que te llegan se guardan en la carpeta QSL y su contacto se marca con la ★."));
        anadirServicio.accept("LoTW", pestana.apply(new String[][]{
                {"Usuario (web de LoTW):", "LOTW_USER", "T", "MAY"},
                {"Contraseña (web de LoTW):", "LOTW_PWD", "P"},
                {"Bajar mis confirmaciones (pone la ★)", "LOTW_BAJAR", "C"}},
                "Son el usuario y la contraseña con los que entras en la web lotw.arrl.org (no hace falta el certificado de TQSL para bajar). "
                        + "LoTW no tiene tarjetas: solo dice qué contactos están confirmados, que son los que cuentan para el DXCC."));
        anadirServicio.accept("Club Log", pestana.apply(new String[][]{
                {"E-mail:", "CLUBLOG_EMAIL", "T"},
                {"Contraseña:", "CLUBLOG_PWD", "P"},
                {"Indicativo:", "CLUBLOG_CALL", "T", "MAY"},
                {"Subir cada contacto", "CLUBLOG_SUBIR_AUTO", "C"}}, null));
        anadirServicio.accept("HRDLog", pestana.apply(new String[][]{
                {"Usuario:", "HRDLOG_USER", "T", "MAY"},
                {"Código de subida:", "HRDLOG_CODE", "T"},
                {"Subir cada contacto", "HRDLOG_SUBIR_AUTO", "C"},
                {"HRDLog ON AIR", "HRDLOG_ONAIR", "C"}},
                "El código de subida (Upload code) NO es tu contraseña de HRDLOG.net: lo encontrarás en tu perfil de HRDLOG.net (HRDLOG Profile)."));
        anadirServicio.accept("HamQTH", pestana.apply(new String[][]{
                {"Usuario:", "HAMQTH_USER", "T", "MAY"},
                {"Contraseña:", "HAMQTH_PWD", "P"},
                {"Indicativo:", "HAMQTH_CALL", "T", "MAY"},
                {"Subir cada contacto", "HAMQTH_SUBIR_AUTO", "C"}}, null));
        anadirServicio.accept("Cluster URE", pestana.apply(new String[][]{
                {"Indicativo:", "CLUSTER_URE_CALL", "T", "MAY"},
                {"Contraseña:", "CLUSTER_URE_PWD", "P"}},
                "Para anunciar contactos en el cluster de URE (ea4ure.com). Si lo dejas vacío, se entra con tu indicativo y sin contraseña. "
                        + "Si el cluster te pide registrarte, hazlo en su web y pon aquí la contraseña."));
        anadirServicio.accept("Cluster DXFun", pestana.apply(new String[][]{
                {"Indicativo:", "CLUSTER_DXFUN_CALL", "T", "MAY"},
                {"Contraseña:", "CLUSTER_DXFUN_PWD", "P"}},
                "Para anunciar contactos en el cluster de DXFun (dxfun.com). Si lo dejas vacío, se entra con tu indicativo y sin contraseña. "
                        + "Si el cluster te pide registrarte, hazlo en su web y pon aquí la contraseña."));

        JLabel lblError = new JLabel(" ");
        lblError.setFont(new Font("Dialog", Font.BOLD, 12));   // "Dialog" tiene los símbolos ✓ y ✗
        lblError.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
        JButton btnGuardar = new JButton("Guardar");
        JButton btnCancelar = new JButton("Cancelar");
        btnCancelar.addActionListener(e -> dialogo.dispose());

        // Lee lo que hay escrito en un campo
        java.util.function.Function<JComponent, String> leerCampo = c -> {
            if (c instanceof JCheckBox chk) return chk.isSelected() ? "1" : "0";
            if (c instanceof JPasswordField pf) return new String(pf.getPassword()).trim();
            if (c instanceof JTextField tf) return tf.getText().trim();
            return "";
        };
        String[][] servicios = {   // nombre, campo principal, casilla de subir
                {"QRZ.COM", "QRZ_USER", "QRZ_SUBIR_AUTO"}, {"eQSL", "EQSL_USER", "EQSL_SUBIR_AUTO"}, {"Club Log", "CLUBLOG_EMAIL", "CLUBLOG_SUBIR_AUTO"},
                {"HRDLog", "HRDLOG_USER", "HRDLOG_SUBIR_AUTO"}, {"HamQTH", "HAMQTH_USER", "HAMQTH_SUBIR_AUTO"}, {"LoTW", "LOTW_USER", "LOTW_BAJAR"},
                {"Cluster URE", "CLUSTER_URE_PWD", null}, {"Cluster DXFun", "CLUSTER_DXFUN_PWD", null}};
        Map<String, JLabel> estados = new HashMap<>();
        Map<String, String> resultadoPrueba = new HashMap<>();   // lo que dijo «Probar» (si se ha probado)
        Runnable pintarEstados = () -> {
            for (String[] sv : servicios) {
                JLabel l = estados.get(sv[0]);
                if (l == null) continue;
                boolean conf = !leerCampo.apply(campos.get(sv[1])).isEmpty();
                boolean sube = sv[2] != null && "1".equals(leerCampo.apply(campos.get(sv[2])));
                String prueba = resultadoPrueba.get(sv[0]);
                if (prueba != null && prueba.startsWith("✗")) l.setText("<html><font color='#ff6b5e'>" + prueba + "</font></html>");
                else if (!conf) l.setText("<html><font color='#8796ad'>sin configurar</font></html>");
                else l.setText("<html><font color='#4cd07d'>" + ("✓".equals(prueba) ? "✓ probado: funciona" : "✓ configurado") + "</font>"
                        + (sv[2] == null ? " · para anunciar" : "LoTW".equals(sv[0]) ? (sube ? " · baja confirmaciones" : " · no baja nada")
                        : sube ? " · sube cada contacto" : " · no sube contactos") + "</html>");
            }
        };
        JButton btnProbar = new JButton("Probar");
        // Probar: comprueba con el servicio los datos escritos (sin subir ningún contacto).
        // Se puede hacer desde la lista (botón «Probar» de cada servicio) o dentro de «Configurar».
        List<JButton> botonesProbar = new ArrayList<>();
        botonesProbar.add(btnProbar);
        java.util.function.Consumer<String> probar = servicioSel -> {
            if (servicioSel == null || servicioSel.isEmpty()) return;
            java.util.function.Function<String, String> v = k -> campos.containsKey(k) ? leerCampo.apply(campos.get(k)) : "";
            Map<String, String> datos = new HashMap<>();
            for (String k : campos.keySet()) datos.put(k, v.apply(k));
            for (JButton b : botonesProbar) b.setEnabled(false);
            lblError.setForeground(TEMA_TEXTO);
            lblError.setText("Probando " + servicioSel + "…");
            new Thread(() -> {
                String[] res = probarServicioOnline(servicioSel, datos);
                SwingUtilities.invokeLater(() -> {
                    for (JButton b : botonesProbar) b.setEnabled(true);
                    if ("ERROR".equals(res[0]) || (!"OK".equals(res[0]) && !"AVISO".equals(res[0]))) resultadoPrueba.put(servicioSel, "✗ " + res[1]);
                    else if ("OK".equals(res[0])) resultadoPrueba.put(servicioSel, "✓");
                    else resultadoPrueba.remove(servicioSel);
                    lblError.setForeground("OK".equals(res[0]) ? cat.VERDE_ENCENDIDO : "AVISO".equals(res[0]) ? TEMA_AMBAR : new Color(255, 80, 80));
                    // En varias líneas si no cabe (el mensaje puede ser largo)
                    lblError.setText("<html><div style='width:330px'>" + ("OK".equals(res[0]) ? "✓ " : "AVISO".equals(res[0]) ? "" : "✗ ") + servicioSel + ": "
                            + res[1].replace("&", "&amp;").replace("<", "&lt;") + "</div></html>");
                    if (servVisible[0].isEmpty()) pintarEstados.run();
                    Window w = SwingUtilities.getWindowAncestor(lblError);
                    if (w != null && w.getHeight() < w.getPreferredSize().height) w.pack();
                });
            }, "ProbarServicio").start();
        };
        // Barra encima de cada servicio: «← Todos los servicios» y su nombre
        JButton bVolver = new JButton("← Todos los servicios");
        bVolver.setFont(new Font("Dialog", Font.PLAIN, 12));
        JLabel lblServ = new JLabel();
        lblServ.setFont(new Font("Arial", Font.BOLD, 15));
        lblServ.setForeground(TEMA_AMBAR);
        JPanel barraServ = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        barraServ.add(bVolver);
        barraServ.add(lblServ);
        java.util.function.Consumer<String> mostrar = nombre -> {
            servVisible[0] = nombre;
            if (!nombre.isEmpty() && "✓".equals(resultadoPrueba.get(nombre))) resultadoPrueba.remove(nombre);   // si se cambia algo, hay que volver a probar
            barraServ.setVisible(!nombre.isEmpty());
            lblServ.setText(nombre);
            cartas.show(tarjetas, nombre.isEmpty() ? "LISTA" : nombre);
            btnProbar.setVisible(!nombre.isEmpty());
            if (nombre.isEmpty()) pintarEstados.run();
        };
        JPanel lista = new JPanel(new GridBagLayout());
        lista.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        GridBagConstraints gl = new GridBagConstraints();
        gl.insets = new Insets(7, 6, 7, 6);
        gl.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < servicios.length; i++) {
            String nombre = servicios[i][0];
            gl.gridy = i;
            gl.gridx = 0; gl.weightx = 0;
            JLabel n = new JLabel(nombre);
            n.setFont(new Font("Arial", Font.BOLD, 14));
            n.setPreferredSize(new Dimension(100, 22));
            lista.add(n, gl);
            gl.gridx = 1; gl.weightx = 1;
            JLabel est = new JLabel();
            est.setFont(new Font("Dialog", Font.PLAIN, 12));   // "Dialog" tiene ✓ y ✗
            estados.put(nombre, est);
            lista.add(est, gl);
            gl.gridx = 2; gl.weightx = 0;
            JButton bProbarFila = new JButton("Probar");
            bProbarFila.setToolTipText("Comprueba con " + nombre + " que los datos son correctos (no sube nada)");
            bProbarFila.addActionListener(e -> probar.accept(nombre));
            botonesProbar.add(bProbarFila);
            lista.add(bProbarFila, gl);
            gl.gridx = 3;
            JButton bConf = new JButton("Configurar");
            bConf.addActionListener(e -> mostrar.accept(nombre));
            lista.add(bConf, gl);
        }
        gl.gridy = servicios.length; gl.gridx = 0; gl.gridwidth = 4; gl.weighty = 1;
        lista.add(Box.createVerticalGlue(), gl);
        tarjetas.add(lista, "LISTA");
        final String[] opVisible = {opInicial};
        Runnable recogerOperador = () -> {
            Map<String, String> m = datosDe.apply(opVisible[0]);
            for (String k : CLAVES_SERVICIO_OPERADOR) if (campos.containsKey(k)) m.put(k, leerCampo.apply(campos.get(k)));
        };
        comboOp.addActionListener(e -> {
            Object sel = comboOp.getSelectedItem();
            String nuevo = sel == null ? "" : sel.toString().trim().toUpperCase();
            if (nuevo.equals(opVisible[0])) return;
            recogerOperador.run();
            opVisible[0] = nuevo;
            Map<String, String> m = datosDe.apply(nuevo);
            for (String k : CLAVES_SERVICIO_OPERADOR) {
                JComponent c = campos.get(k);
                if (c instanceof JCheckBox chk) chk.setSelected("1".equals(m.get(k)));
                else if (c instanceof JTextField tf) tf.setText(m.getOrDefault(k, ""));
            }
            resultadoPrueba.clear();
            pintarEstados.run();
        });

        String usrAntes = ConfigApp.get("QRZ_USER", ""), pwdAntes = ConfigApp.get("QRZ_PWD", "");
        Runnable guardarTodo = () -> {
            recogerOperador.run();
            for (Map.Entry<String, JComponent> en : campos.entrySet()) {
                if (deOperador.contains(en.getKey())) continue;   // estos van por operador (abajo)
                ConfigApp.put(en.getKey(), leerCampo.apply(en.getValue()));
            }
            for (Map.Entry<String, Map<String, String>> op : borrador.entrySet()) {
                if (op.getKey().isEmpty()) continue;
                for (Map.Entry<String, String> d : op.getValue().entrySet())
                    ConfigApp.put(claveServicio(op.getKey(), d.getKey()), d.getValue() == null ? "" : d.getValue());
            }
            qrzSessionKey = null;
            if (lblQrzIcon != null) lblQrzIcon.setVisible(false);
            JOptionPane.showMessageDialog(dialogo, "Configuración de los servicios online guardada correctamente.", "Éxito", JOptionPane.INFORMATION_MESSAGE);
            dialogo.dispose();
        };
        btnGuardar.addActionListener(e -> {
            String usr = ((JTextField) campos.get("QRZ_USER")).getText().trim();
            String pwd = new String(((JPasswordField) campos.get("QRZ_PWD")).getPassword()).trim();
            // Si se han cambiado el usuario o la contraseña de QRZ.com, se comprueban antes de guardar
            if (usr.isEmpty() || (usr.equals(usrAntes) && pwd.equals(pwdAntes))) { guardarTodo.run(); return; }
            if (pwd.isEmpty()) {
                mostrar.accept("QRZ.COM");
                lblError.setForeground(new Color(255, 80, 80));
                lblError.setText("QRZ.COM: escribe también la contraseña.");
                return;
            }
            btnGuardar.setEnabled(false);
            lblError.setForeground(TEMA_TEXTO);
            lblError.setText("Comprobando el usuario y la contraseña con QRZ.com…");
            new Thread(() -> {
                String res = comprobarQrz(usr, pwd);
                SwingUtilities.invokeLater(() -> {
                    btnGuardar.setEnabled(true);
                    lblError.setForeground(new Color(255, 80, 80));
                    if (res.equals("OK")) {
                        lblError.setText(" ");
                        guardarTodo.run();
                    } else if (res.equals("RED")) {
                        lblError.setText("No se ha podido conectar con QRZ.com para comprobarlo.");
                        int rr = JOptionPane.showConfirmDialog(dialogo,
                                "No se ha podido comprobar el usuario y la contraseña de QRZ.com (¿sin internet?).\n¿Guardar de todas formas?",
                                "QRZ.COM", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                        if (rr == JOptionPane.YES_OPTION) guardarTodo.run();
                    } else {
                        mostrar.accept("QRZ.COM");
                        lblError.setText("QRZ.COM: el usuario o la contraseña no coinciden.");
                    }
                });
            }, "ComprobarQRZ").start();
        });

        JPanel abajo = new JPanel(new BorderLayout());
        abajo.add(lblError, BorderLayout.CENTER);
        btnProbar.setToolTipText("Comprueba con el servicio que los datos escritos son correctos (no sube nada)");
        btnProbar.addActionListener(e -> probar.accept(servVisible[0]));
        JPanel bot = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bot.add(btnProbar);
        bot.add(btnCancelar);
        bot.add(btnGuardar);
        abajo.add(bot, BorderLayout.EAST);
        JPanel arriba = new JPanel(new BorderLayout(8, 4));
        arriba.setBorder(BorderFactory.createEmptyBorder(10, 14, 0, 14));
        JPanel filaOp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel lblOp = new JLabel("Cuentas del operador:");
        lblOp.setFont(new Font("Arial", Font.BOLD, 13));
        filaOp.add(lblOp);
        filaOp.add(comboOp);
        arriba.add(filaOp, BorderLayout.NORTH);
        JLabel notaOp = new JLabel("<html><div style='width:540px'>Cada operador pone aquí sus propias cuentas. Los contactos se suben "
                + "a las cuentas del operador que los hizo. (El usuario y la contraseña de QRZ.com para buscar indicativos son comunes a todos.)</div></html>");
        notaOp.setFont(new Font("Arial", Font.ITALIC, 11));
        arriba.add(notaOp, BorderLayout.CENTER);
        dialogo.add(arriba, BorderLayout.NORTH);
        bVolver.addActionListener(e -> mostrar.accept(""));
        JPanel centro = new JPanel(new BorderLayout());
        centro.add(barraServ, BorderLayout.NORTH);
        centro.add(tarjetas, BorderLayout.CENTER);
        mostrar.accept("");
        dialogo.add(centro, BorderLayout.CENTER);
        dialogo.add(abajo, BorderLayout.SOUTH);
        dialogo.setSize(640, 480);
        dialogo.setLocationRelativeTo(this);
        dialogo.setVisible(true);
    }

    private void eliminarQSOsSeleccionados() {
        int[] filasSeleccionadas = tablaQSOs.getSelectedRows();
        if (filasSeleccionadas.length == 0) return;

        if (mostrandoVistaPuntos) {
            int numeralActividad = obtenerNumeralDeActividadDesdeSeleccion();
            if (numeralActividad <= 0) return;

            java.util.List<String> indicativos = new java.util.ArrayList<>();
            for (int fila : filasSeleccionadas) {
                int modelRow = tablaQSOs.convertRowIndexToModel(fila);
                Object ind = modeloPuntos.getValueAt(modelRow, 1);
                if (ind != null) indicativos.add(ind.toString());
            }

            if (indicativos.isEmpty()) return;

            String lista = String.join(", ", indicativos);
            int confirmacion = JOptionPane.showConfirmDialog(this,
                    "Vas a quitar de esta actividad TODOS los QSOs de:\n\n" + lista +
                            "\n\nLos QSOs NO se borrarán: seguirán en el LOG Principal de cada operador.\n" +
                            "¿Continuar?",
                    "Confirmar", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirmacion != JOptionPane.YES_OPTION) return;

            try (Connection conn = DatabaseManager.conectar();
                 PreparedStatement pstmt = conn.prepareStatement(
                         "UPDATE qsos SET actividad_numeral = 0 WHERE actividad_numeral = ? AND indicativo = ?")) {
                for (String ind : indicativos) {
                    pstmt.setInt(1, numeralActividad);
                    pstmt.setString(2, ind);
                    pstmt.executeUpdate();
                }
                cargarQSOsDesdeBaseDatos();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
            return;
        }

        // Si hay una actividad seleccionada, "eliminar" solo quita el QSO de la actividad:
        // el QSO se conserva en el LOG Principal. Solo en el LOG Principal se borra de verdad.
        int numeralActividadActual = obtenerNumeralDeActividadDesdeSeleccion();
        boolean quitarSoloDeActividad = numeralActividadActual > 0;
        // RADIOCLUB: en el LOG Radioclub, "eliminar" solo lo quita del log del radioclub
        boolean quitarSoloDeRadioclub = !quitarSoloDeActividad && radioclub.esVistaRadioclubSeleccionada();

        // Cuántos de los seleccionados son contactos del radioclub (se comparten con los demás ordenadores)
        int delRadioclub = 0;
        List<String> uuidsRadioclub = new ArrayList<>();
        if (!quitarSoloDeActividad) {
            try (Connection conn = DatabaseManager.conectar();
                 PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(uuid,'') FROM qsos WHERE id = ? AND COALESCE(radioclub,0) = 1")) {
                for (int f : filasSeleccionadas) {
                    Object idObj = modeloTabla.getValueAt(tablaQSOs.convertRowIndexToModel(f), COL_ESTANDAR_ID);
                    if (idObj == null) continue;
                    ps.setInt(1, (int) idObj);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { delRadioclub++; uuidsRadioclub.add(rs.getString(1)); } }
                }
            } catch (Exception ignored) { }
        }
        boolean soloEnEsteOrdenador = false;
        int confirmacion;
        if (quitarSoloDeRadioclub) {
            confirmacion = JOptionPane.showConfirmDialog(this,
                    "Vas a quitar " + filasSeleccionadas.length + " QSOs del " + NOMBRE_VISTA_RC + ".\n\n" +
                            "OJO: se quitarán del log del radioclub en los ordenadores de TODOS los socios.\n" +
                            "Los QSOs NO se borrarán: seguirán en el LOG Principal de su operador.\n" +
                            "¿Continuar?",
                    "Confirmar", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        } else if (quitarSoloDeActividad) {
            confirmacion = JOptionPane.showConfirmDialog(this,
                    "Vas a quitar los QSOs seleccionados de esta actividad.\n\n" +
                            "Los QSOs NO se borrarán: seguirán en el LOG Principal.\n" +
                            "¿Continuar?",
                    "Confirmar", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        } else if (delRadioclub > 0 && radioclub.syncActivo()) {
            // Contactos del radioclub: se pregunta claramente de dónde se borran
            Object[] op = {"Solo de mi ordenador", "De mi ordenador y del radioclub (todos)", "Cancelar"};
            int e = JOptionPane.showOptionDialog(this,
                    "Vas a borrar " + filasSeleccionadas.length + " contactos. " + delRadioclub + " de ellos son del radioclub.\n\n" +
                            "• «Solo de mi ordenador»: desaparecen aquí, pero los demás socios los siguen teniendo.\n" +
                            "• «De mi ordenador y del radioclub»: se borran también en los ordenadores de TODOS los socios.\n\n" +
                            "¿De dónde quieres borrarlos?",
                    "¿Borrar " + filasSeleccionadas.length + " contactos?", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, op, op[2]);
            if (e == 0) { soloEnEsteOrdenador = true; confirmacion = JOptionPane.YES_OPTION; }
            else if (e == 1) confirmacion = JOptionPane.YES_OPTION;
            else confirmacion = JOptionPane.NO_OPTION;
        } else {
            confirmacion = JOptionPane.showConfirmDialog(this,
                    "Vas a borrar " + filasSeleccionadas.length + " contactos de este ordenador.\n¿Continuar?",
                    "Confirmar eliminación", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        }

        if (confirmacion == JOptionPane.YES_OPTION) {
            for (int f : filasSeleccionadas) {
                Object idObj = modeloTabla.getValueAt(tablaQSOs.convertRowIndexToModel(f), COL_ESTANDAR_ID);
                if (idObj != null && !radioclub.puedeTocarQso((int) idObj, true)) return;
            }
            try (Connection conn = DatabaseManager.conectar()) {
                // Indicativos de los contactos que se quitan (para la lista BANDA CIUDADANA)
                List<String> indicativosQuitados = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement("SELECT indicativo FROM qsos WHERE id = ? AND UPPER(banda) = 'CB'")) {
                    for (int f : filasSeleccionadas) {
                        Object idObj = modeloTabla.getValueAt(tablaQSOs.convertRowIndexToModel(f), COL_ESTANDAR_ID);
                        if (idObj == null) continue;
                        ps.setInt(1, (int) idObj);
                        try (ResultSet rs = ps.executeQuery()) { if (rs.next() && rs.getString(1) != null) indicativosQuitados.add(rs.getString(1)); }
                    }
                }
                String sql = quitarSoloDeRadioclub
                        ? "UPDATE qsos SET radioclub = 0 WHERE id = ?"
                        : quitarSoloDeActividad
                        ? "UPDATE qsos SET actividad_numeral = 0 WHERE id = ?"
                        : "DELETE FROM qsos WHERE id = ?";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    for (int i = filasSeleccionadas.length - 1; i >= 0; i--) {
                        int modelRow = tablaQSOs.convertRowIndexToModel(filasSeleccionadas[i]);
                        Object idObj = modeloTabla.getValueAt(modelRow, COL_ESTANDAR_ID);
                        if (idObj != null) {
                            int idQso = (int) idObj;
                            if (!quitarSoloDeRadioclub && !quitarSoloDeActividad) Radioclub.guardarEnPapeleraPorId(conn, idQso, "Yo");   // se puede recuperar
                            pstmt.setInt(1, idQso);
                            pstmt.executeUpdate();
                            modeloTabla.removeRow(modelRow);
                        }
                    }
                }
                if (soloEnEsteOrdenador) {
                    // Que la sincronización no lo tome como un borrado para todos: se olvida que estaba compartido
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sync_pub WHERE tipo = 'QSO' AND clave = ?")) {
                        for (String u : uuidsRadioclub) { if (u.isEmpty()) continue; ps.setString(1, u); ps.executeUpdate(); }
                    }
                } else {
                    for (String ind : indicativosQuitados) quitarDeListaCBSiNoQuedaEnRadioclub(conn, ind);
                }
                actualizarEstado();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error al eliminar el QSO: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void generarDiploma() {
        String actividadSeleccionada = (cbSelectorActividad != null) ? (String) cbSelectorActividad.getSelectedItem() : null;

        if (actividadSeleccionada == null || actividadSeleccionada.equals("LOG Principal")
                || actividadSeleccionada.equals(NOMBRE_VISTA_CB) || actividadSeleccionada.equals(NOMBRE_VISTA_RC)) {
            JOptionPane.showMessageDialog(this, "Por favor, seleccione una Actividad específica en el desplegable superior para configurar su diploma.", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }

        File archivoAdi = exportarAdiParaActividad(actividadSeleccionada);
        if (archivoAdi == null) {
            JOptionPane.showMessageDialog(this,
                    "No se pudo preparar el archivo ADI de la actividad.\nRevisa los permisos de la carpeta de Documents.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            GeneradorDiplomaUI diplomaUI = new GeneradorDiplomaUI(actividadSeleccionada, archivoAdi);
            if (horaLocal()) diplomaUI.usarHoraLocalPorDefecto();
            // Si el diploma de esta actividad ya se configuró, se abre tal cual se dejó
            int numeralDip = obtenerNumeralDeActividadDesdeSeleccion();
            diplomaUI.prepararConfiguracion(numeralDip);
            diplomaUI.setVisible(true);
        });
    }

    private File exportarAdiParaActividad(String actividad) {
        try {
            File base = new File(System.getProperty("user.home"),
                    "Documents" + File.separator + NOMBRE_APP);
            if (!base.exists()) base.mkdirs();

            String nombreCarpeta = actividad.replaceAll("[\\\\/:*?\"<>|]", "_");
            File carpetaActividad = new File(base, nombreCarpeta);
            if (!carpetaActividad.exists()) carpetaActividad.mkdirs();

            File archivoTemp = new File(carpetaActividad, "actividad_" + nombreCarpeta + ".adi");

            // Se filtra por el numeral de la actividad (igual que las vistas de la tabla):
            // el texto del desplegable ("1 - NOMBRE") no coincide con el nombre guardado en la BD.
            int numeralAct = obtenerNumeralDeActividadDesdeSeleccion();
            String sql = numeralAct > 0
                    ? "SELECT fecha, hora, indicativo, banda, modo, freq_tx, freq_rx, rst_sent, rst_rcvd, nombre, qth, grid, actividad, operador, estacion FROM qsos WHERE actividad_numeral = ?"
                    : "SELECT fecha, hora, indicativo, banda, modo, freq_tx, freq_rx, rst_sent, rst_rcvd, nombre, qth, grid, actividad, operador, estacion FROM qsos WHERE actividad = ?";
            try (Connection conn = DatabaseManager.conectar();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                if (numeralAct > 0) {
                    pstmt.setInt(1, numeralAct);
                } else {
                    pstmt.setString(1, actividad);
                }

                try (ResultSet rs = pstmt.executeQuery();
                     FileWriter writer = new FileWriter(archivoTemp)) {

                    writer.write("ADIF Export for Activity " + actividad + " generated by Bitacora 26 EA4IXR\n");
                    writer.write("<ADIF_VER:5>3.1.4\n");
                    writer.write("<EOH>\n\n");

                    while (rs.next()) {
                        String call = rs.getString("indicativo");
                        String fechaRaw = rs.getString("fecha");
                        String horaRaw = rs.getString("hora");
                        String banda = rs.getString("banda");
                        String modo = rs.getString("modo");
                        String freqTx = rs.getString("freq_tx");
                        String rstS = rs.getString("rst_sent");
                        String rstR = rs.getString("rst_rcvd");
                        String nombre = rs.getString("nombre");
                        String qth = rs.getString("qth");
                        String grid = rs.getString("grid");
                        String act = rs.getString("actividad");

                        String fechaAdi = "";
                        if (fechaRaw != null && fechaRaw.contains("/")) {
                            String[] partes = fechaRaw.split("/");
                            if (partes.length == 3) {
                                fechaAdi = partes[2] + partes[1] + partes[0];
                            }
                        }

                        String horaAdi = "";
                        if (horaRaw != null) {
                            horaAdi = horaRaw.replace(":", "") + "00";
                            if (horaAdi.length() > 6) horaAdi = horaAdi.substring(0, 6);
                        }

                        if (call != null && !call.isEmpty()) writer.write("<CALL:" + call.length() + ">" + call + " ");
                        if (!fechaAdi.isEmpty()) writer.write("<QSO_DATE:8>" + fechaAdi + " ");
                        if (!horaAdi.isEmpty()) writer.write("<TIME_ON:" + horaAdi.length() + ">" + horaAdi + " ");
                        if (banda != null && !banda.isEmpty())
                            writer.write("<BAND:" + banda.length() + ">" + banda + " ");
                        if ("FT4".equalsIgnoreCase(modo)) writer.write("<MODE:4>MFSK <SUBMODE:3>FT4 ");   // FT4 en ADIF
                        else if (modo != null && !modo.isEmpty()) writer.write("<MODE:" + modo.length() + ">" + modo + " ");
                        if (freqTx != null && !freqTx.isEmpty())
                            writer.write("<FREQ:" + freqTx.length() + ">" + freqTx + " ");
                        if (rstS != null && !rstS.isEmpty())
                            writer.write("<RST_SENT:" + rstS.length() + ">" + rstS + " ");
                        if (rstR != null && !rstR.isEmpty())
                            writer.write("<RST_RCVD:" + rstR.length() + ">" + rstR + " ");
                        if (nombre != null && !nombre.isEmpty())
                            writer.write("<NAME:" + nombre.length() + ">" + nombre + " ");
                        if (qth != null && !qth.isEmpty()) writer.write("<QTH:" + qth.length() + ">" + qth + " ");
                        if (grid != null && !grid.isEmpty())
                            writer.write("<GRIDSQUARE:" + grid.length() + ">" + grid + " ");
                        if (act != null && !act.isEmpty())
                            writer.write("<MY_SOTA_REF:" + act.length() + ">" + act + " ");
                        String operadorRow = rs.getString("operador");
                        if (operadorRow != null && !operadorRow.isEmpty())
                            writer.write("<OPERATOR:" + operadorRow.length() + ">" + operadorRow + " ");
                        String estacionRow = rs.getString("estacion");
                        if (estacionRow != null && !estacionRow.isEmpty())
                            writer.write("<STATION_CALLSIGN:" + estacionRow.length() + ">" + estacionRow + " ");

                        writer.write("<EOR>\n");
                    }
                }
            }
            return archivoTemp;
        } catch (Exception e) {
            return null;
        }
    }

    private void buscarDatosIndicativo(String callsign) {
        if (callsign == null || callsign.trim().isEmpty()) {
            txtNombre.setText("");
            txtQth.setText("");
            txtGrid.setText("");
            alternarModoNombre(false);
            if (lblQrzIcon != null) lblQrzIcon.setVisible(false);
            return;
        }

        if (txtIndicativo == null) return;
        String textoActual = txtIndicativo.getText().trim();
        if (!textoActual.equalsIgnoreCase(callsign.trim())) {
            return;
        }

        final String call = callsign.trim().toUpperCase();

        if (lblQrzIcon != null) lblQrzIcon.setVisible(false);

        // Banda CB: QRZ.com es solo para radioaficionados, así que nunca se consulta.
        // Se busca en la lista de Banda Ciudadana (tiene prioridad) y después en los contactos guardados.
        final boolean esCB = "CB".equalsIgnoreCase(String.valueOf(cbBanda.getSelectedItem()));
        // En CB el mismo indicativo puede ser de varias personas (distinto QRA o QTH): se juntan la lista de
        // Banda Ciudadana y los contactos guardados, y si hay más de uno se elige en el desplegable
        final String[] datosCB = esCB ? buscarEnDirectorioCB(call) : null;

        List<String[]> coincidencias = new ArrayList<>();
        String sql = "SELECT nombre, qth, grid FROM qsos WHERE indicativo = ? AND (nombre != '' OR qth != '' OR grid != '') ORDER BY nombre ASC";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, call);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String nombreBD = rs.getString("nombre");
                    String qthBD = rs.getString("qth");
                    String gridBD = rs.getString("grid");

                    if (nombreBD == null || nombreBD.isEmpty()) continue;

                    boolean duplicado = false;
                    for (String[] existente : coincidencias) {
                        if (existente[0].equalsIgnoreCase(nombreBD)
                                && (existente[1] == null ? "" : existente[1]).equalsIgnoreCase(qthBD == null ? "" : qthBD)
                                && (existente[2] == null ? "" : existente[2]).equalsIgnoreCase(gridBD == null ? "" : gridBD)) {
                            duplicado = true;
                            break;
                        }
                    }
                    if (!duplicado) {
                        coincidencias.add(new String[]{
                                nombreBD,
                                qthBD != null ? qthBD : "",
                                gridBD != null ? gridBD : ""
                        });
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (datosCB != null) {
            boolean yaEsta = false;
            for (String[] ex : coincidencias) {
                if (ex[0].equalsIgnoreCase(datosCB[0]) && ex[1].equalsIgnoreCase(datosCB[1])) { yaEsta = true; break; }
            }
            if (!yaEsta) coincidencias.add(0, new String[]{datosCB[0], datosCB[1], ""});
        }

        if (coincidencias.isEmpty()) {
            txtNombre.setText("");
            txtQth.setText("");
            txtGrid.setText("");
            alternarModoNombre(false);
            // Después de los datos propios (y en CB, la lista BANDA CIUDADANA): la base de datos de Bitácora 26
            // (solo con cuenta); si tampoco está ahí, QRZ.com / HamQTH (nunca en CB)
            if (cuentaB26 != null && !CuentaBitacora.cuentasB26().isEmpty()) {
                new Thread(() -> {
                    List<String[]> fichas = cuentaB26.buscarFichasB26(call, esCB);
                    SwingUtilities.invokeLater(() -> {
                        // (aquí no vale indicativoActualEs: esa no deja rellenar en CB, y estas fichas sí valen para CB)
                        if (txtIndicativo == null || !txtIndicativo.getText().trim().equalsIgnoreCase(call)
                                || esCB != "CB".equalsIgnoreCase(String.valueOf(cbBanda.getSelectedItem()))) return;
                        if (fichas != null && fichas.size() == 1) {
                            String[] d = fichas.getFirst();
                            if (txtNombre.getText().isEmpty() && !d[0].isEmpty()) txtNombre.setText(d[0]);
                            if (txtQth.getText().isEmpty() && !d[1].isEmpty()) txtQth.setText(d[1]);
                            if (txtGrid.getText().isEmpty() && !d[2].isEmpty()) txtGrid.setText(d[2]);
                            lblEstado.setText("Datos de " + call + " cargados desde Bitácora 26.");
                        } else if (fichas != null && fichas.size() > 1) {
                            mostrarDesplegableNombres(fichas);
                            lblEstado.setText("Hay " + fichas.size() + " operadores con el indicativo " + call + " en Bitácora 26. Selecciona uno en el desplegable Nombre.");
                        } else {
                            buscarFueraSinDatosPropios(call, esCB);
                        }
                    });
                }, "BuscarFichaB26").start();
                return;
            }
            buscarFueraSinDatosPropios(call, esCB);
            return;
        }
        buscarDatosIndicativoConCoincidencias(call, esCB, coincidencias);
    }

    /** Sin datos propios ni en Bitácora 26: QRZ.com o HamQTH (nunca en CB). */
    private void buscarFueraSinDatosPropios(String call, boolean esCB) {
        {
            String usuarioQrz = ConfigApp.get("QRZ_USER", "");
            String pwdQrz = ConfigApp.get("QRZ_PWD", "");
            if (!esCB && !usuarioQrz.isEmpty() && !pwdQrz.isEmpty()) {
                consultarQrz(call, usuarioQrz, pwdQrz);
            } else if (!esCB && hayCuentaHamQth()) {
                // Sin cuenta de QRZ.com: se busca en HamQTH
                new Thread(() -> {
                    String[] hq = buscarEnHamQth(call);
                    if (hq != null) rellenarFormularioDesdeServicio(call, hq, "HamQTH");
                }, "BuscarHamQTH").start();
            }
        }
    }

    private void buscarDatosIndicativoConCoincidencias(String call, boolean esCB, List<String[]> coincidencias) {
        if (coincidencias.size() == 1) {
            String[] unico = coincidencias.getFirst();
            txtNombre.setText(unico[0]);
            txtQth.setText(unico[1]);
            txtGrid.setText(unico[2]);
            alternarModoNombre(false);
            lblEstado.setText("Datos de " + call + " cargados desde " + (esCB ? "la lista de Banda Ciudadana." : "la Base de Datos Local."));

            String usuarioQrz = ConfigApp.get("QRZ_USER", "");
            String pwdQrz = ConfigApp.get("QRZ_PWD", "");
            if (!esCB && !usuarioQrz.isEmpty() && !pwdQrz.isEmpty()) {
                verificarExistenciaQrz(call, usuarioQrz, pwdQrz);
            }
            return;
        }

        txtNombre.setText("");
        txtQth.setText("");
        txtGrid.setText("");
        mostrarDesplegableNombres(coincidencias);
        lblEstado.setText("Hay " + coincidencias.size() + " operadores con el indicativo " + call + ". Selecciona uno en el desplegable Nombre.");

        String usuarioQrz = ConfigApp.get("QRZ_USER", "");
        String pwdQrz = ConfigApp.get("QRZ_PWD", "");
        if (!esCB && !usuarioQrz.isEmpty() && !pwdQrz.isEmpty()) {
            verificarExistenciaQrz(call, usuarioQrz, pwdQrz);
        }
    }

    /** Nombre y QTH de un indicativo en la lista de Banda Ciudadana, o null si no está o no tiene datos. */
    private String[] buscarEnDirectorioCB(String call) {
        String sql = "SELECT nombre, qth FROM cb_directorio WHERE indicativo = ? AND eliminado = 0";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, call);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String nombre = txtCB(rs.getString(1));
                    String qth = txtCB(rs.getString(2));
                    if (!nombre.isEmpty() || !qth.isEmpty()) return new String[]{nombre, qth};
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Mantiene al día la lista de Banda Ciudadana con los contactos CB guardados (sin mostrar errores). */
    private void sincronizarDirectorioCBEnSegundoPlano() {
        try (Connection conn = DatabaseManager.conectar()) {
            sincronizarDirectorioCB(conn);
        } catch (Exception ex) {
            System.err.println("No se pudo actualizar la lista de Banda Ciudadana: " + ex.getMessage());
        }
    }

    private void alternarModoNombre(boolean usarCombo) {
        if (panelNombreContenedor == null || cardLayoutNombre == null) return;

        if (usarCombo) {
            cardLayoutNombre.show(panelNombreContenedor, CARD_COMBO);
        } else {
            cardLayoutNombre.show(panelNombreContenedor, CARD_TEXTO);
        }
        panelNombreContenedor.revalidate();
        panelNombreContenedor.repaint();
    }

    private void mostrarDesplegableNombres(List<String[]> opciones) {
        opcionesNombres.clear();
        cbNombres.removeAllItems();
        cbNombres.addItem(OPCION_SELECCIONAR);

        for (String[] op : opciones) {
            String nombre = op[0];
            String qth = op[1] != null ? op[1] : "";
            String grid = op[2] != null ? op[2] : "";

            String etiqueta = qth.isEmpty() ? nombre : nombre + " — " + qth;
            cbNombres.addItem(etiqueta);
            opcionesNombres.put(etiqueta, new String[]{nombre, qth, grid});
        }

        cbNombres.addItem(OPCION_OTRO);
        cbNombres.setSelectedIndex(0);
        alternarModoNombre(true);
    }

    private void seleccionarOperadorDelCombo() {
        Object sel = cbNombres.getSelectedItem();
        if (sel == null) return;

        String seleccionado = sel.toString();

        if (OPCION_SELECCIONAR.equals(seleccionado)) {
            return;
        }

        if (OPCION_OTRO.equals(seleccionado)) {
            txtNombre.setText("");
            txtQth.setText("");
            txtGrid.setText("");
            alternarModoNombre(false);
            txtNombre.requestFocus();
            return;
        }

        String[] datos = opcionesNombres.get(seleccionado);
        if (datos != null) {
            txtNombre.setText(datos[0]);
            txtQth.setText(datos[1]);
            txtGrid.setText(datos[2]);
        }
    }

    /** ¿Tiene el operador actual puesta su cuenta de HamQTH (Servicios Online)? */
    private boolean hayCuentaHamQth() {
        String op = operadorActual == null ? "" : operadorActual;
        return !servicio(op, "HAMQTH_USER", "").trim().isEmpty() && !servicio(op, "HAMQTH_PWD", "").isEmpty();
    }

    /** Pone en el formulario (solo en las casillas vacías) los datos encontrados en un servicio. */
    private void rellenarFormularioDesdeServicio(String call, String[] datos, String servicioNombre) {
        SwingUtilities.invokeLater(() -> {
            if (!indicativoActualEs(call)) return;
            if (txtNombre.getText().isEmpty() && !datos[0].isEmpty()) txtNombre.setText(datos[0].toUpperCase());
            if (txtQth.getText().isEmpty() && !datos[1].isEmpty()) txtQth.setText(datos[1].toUpperCase());
            if (txtGrid.getText().isEmpty() && !datos[2].isEmpty()) txtGrid.setText(datos[2].toUpperCase());
            if (lblQrzIcon != null) lblQrzIcon.setVisible(false);
            lblEstado.setText("Datos de " + call + " cargados desde " + servicioNombre + ".");
        });
    }

    /**
     * Busca un indicativo en HamQTH con la cuenta del operador actual. Devuelve {nombre, qth, grid}
     * o null si no hay cuenta, no está o no se puede consultar. Se llama fuera del hilo de la ventana.
     */
    private String[] buscarEnHamQth(String call) {
        String op = operadorActual == null ? "" : operadorActual;
        String usr = servicio(op, "HAMQTH_USER", "").trim(), pwd = servicio(op, "HAMQTH_PWD", "").trim();
        if (usr.isEmpty() || pwd.isEmpty() || call == null || call.isBlank()) return null;
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(10)).build()) {
            for (int intento = 0; intento < 2; intento++) {
                if (hamqthSesion == null || hamqthSesion.isEmpty()) {
                    String login = "https://www.hamqth.com/xml.php?u=" + URLEncoder.encode(usr, StandardCharsets.UTF_8)
                            + "&p=" + URLEncoder.encode(pwd, StandardCharsets.UTF_8);
                    HttpResponse<String> r = client.send(HttpRequest.newBuilder().uri(URI.create(login))
                            .timeout(java.time.Duration.ofSeconds(15)).header("User-Agent", "Bitacora26").build(), HttpResponse.BodyHandlers.ofString());
                    hamqthSesion = extraerXmlTag(r.body(), "session_id");
                    if (hamqthSesion.isEmpty()) return null;   // usuario o contraseña incorrectos
                }
                String url = "https://www.hamqth.com/xml.php?id=" + URLEncoder.encode(hamqthSesion, StandardCharsets.UTF_8)
                        + "&callsign=" + URLEncoder.encode(call.trim(), StandardCharsets.UTF_8) + "&prg=Bitacora26";
                HttpResponse<String> r = client.send(HttpRequest.newBuilder().uri(URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(15)).header("User-Agent", "Bitacora26").build(), HttpResponse.BodyHandlers.ofString());
                String xml = r.body();
                String error = extraerXmlTag(xml, "error");
                if (error.toLowerCase().contains("session")) { hamqthSesion = null; continue; }   // sesión caducada: se entra otra vez
                if (!error.isEmpty() || !xml.contains("<search")) return null;
                String nombre = extraerXmlTag(xml, "adr_name");
                if (nombre.isEmpty()) nombre = extraerXmlTag(xml, "nick");
                String qth = extraerXmlTag(xml, "qth");
                if (qth.isEmpty()) qth = extraerXmlTag(xml, "adr_city");
                String grid = extraerXmlTag(xml, "grid");
                if (nombre.isEmpty() && qth.isEmpty() && grid.isEmpty()) return null;
                return new String[]{nombre, qth, grid};
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Busca un indicativo en QRZ.com (XML) con la cuenta guardada. Devuelve {nombre, qth, grid} o null. Fuera del hilo de la ventana. */
    private String[] buscarEnQrzSincrono(String call) {
        String usr = ConfigApp.get("QRZ_USER", ""), pwd = ConfigApp.get("QRZ_PWD", "");
        if (usr.isEmpty() || pwd.isEmpty() || call == null || call.isBlank()) return null;
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(10)).build()) {
            for (int intento = 0; intento < 2; intento++) {
                if (qrzSessionKey == null || qrzSessionKey.isEmpty()) {
                    String loginUrl = "https://xmldata.qrz.com/xml/current/?username=" + URLEncoder.encode(usr, StandardCharsets.UTF_8)
                            + ";password=" + URLEncoder.encode(pwd, StandardCharsets.UTF_8) + ";agent=EA4IXR_LogBook";
                    HttpResponse<String> r = client.send(HttpRequest.newBuilder().uri(URI.create(loginUrl))
                            .timeout(java.time.Duration.ofSeconds(15)).header("User-Agent", "EA4IXR-LogBook/1.0").build(), HttpResponse.BodyHandlers.ofString());
                    qrzSessionKey = extraerXmlTag(r.body(), "Key");
                    if (qrzSessionKey.isEmpty()) return null;
                }
                String queryUrl = "https://xmldata.qrz.com/xml/current/?s=" + qrzSessionKey + ";callsign=" + URLEncoder.encode(call.trim(), StandardCharsets.UTF_8);
                HttpResponse<String> r = client.send(HttpRequest.newBuilder().uri(URI.create(queryUrl))
                        .timeout(java.time.Duration.ofSeconds(15)).header("User-Agent", "EA4IXR-LogBook/1.0").build(), HttpResponse.BodyHandlers.ofString());
                String xml = r.body();
                if (xml.contains("Session Timeout") || xml.contains("Invalid session key")) { qrzSessionKey = null; continue; }
                boolean existe = (xml.contains("<Callsign>") || xml.contains("<Callsign ")) && extraerXmlTag(xml, "call").equalsIgnoreCase(call.trim());
                if (!existe) return null;
                String nombre = (extraerXmlTag(xml, "fname") + " " + extraerXmlTag(xml, "name")).trim();
                String qth = extraerXmlTag(xml, "qth");
                if (qth.isEmpty()) qth = extraerXmlTag(xml, "addr2");
                String grid = extraerXmlTag(xml, "grid");
                if (nombre.isEmpty() && qth.isEmpty() && grid.isEmpty()) return null;
                return new String[]{nombre, qth, grid};
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ===================== ACTUALIZAR CONTACTOS: rellenar los datos que faltan =====================
    private JButton btnActualizarContactos;

    /**
     * Botón "Actualizar contactos": en el log o actividad que se está viendo, busca los datos que faltan
     * (nombre, QTH, locator) y rellena solo lo vacío. Orden: datos propios; en CB la lista BANDA CIUDADANA
     * (nunca QRZ.com); en las demás bandas QRZ.com y, si no está, HamQTH.
     */
    private void actualizarContactosQueFaltan() {
        if (mostrandoVistaCB) {
            JOptionPane.showMessageDialog(this, "Elige un log o una actividad: esta vista es la lista de indicativos de Banda Ciudadana.",
                    "Actualizar contactos", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int numeral = obtenerNumeralDeActividadDesdeSeleccion();
        boolean vistaRC = numeral <= 0 && radioclub.esVistaRadioclubSeleccionada();
        String filtro = numeral > 0 ? "actividad_numeral = ?" : vistaRC ? "radioclub = 1" : "operador = ?";
        List<Object[]> pendientes = new ArrayList<>();   // {id, operador, indicativo, banda, nombre, qth, grid}
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement("SELECT id, COALESCE(operador,''), COALESCE(indicativo,''), COALESCE(banda,''), "
                     + "COALESCE(nombre,''), COALESCE(qth,''), COALESCE(grid,'') FROM qsos WHERE " + filtro
                     + " AND (TRIM(COALESCE(nombre,'')) = '' OR TRIM(COALESCE(qth,'')) = '' OR TRIM(COALESCE(grid,'')) = '') ORDER BY id")) {
            if (numeral > 0) ps.setInt(1, numeral);
            else if (!vistaRC) ps.setString(1, operadorActual == null ? "" : operadorActual);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getString(3).trim().isEmpty()) continue;
                    pendientes.add(new Object[]{rs.getInt(1), rs.getString(2), rs.getString(3).trim().toUpperCase(), rs.getString(4),
                            rs.getString(5).trim(), rs.getString(6).trim(), rs.getString(7).trim()});
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error al leer los contactos: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (pendientes.isEmpty()) {
            subirContactosSinSubir(numeral, vistaRC, "Todos los contactos de este log tienen nombre, QTH y locator.");
            return;
        }
        boolean hayQrz = !ConfigApp.get("QRZ_USER", "").isEmpty() && !ConfigApp.get("QRZ_PWD", "").isEmpty();
        boolean hayHamQth = hayCuentaHamQth();
        String fuentes = "tus propios contactos, la lista BANDA CIUDADANA (en CB)"
                + (CuentaBitacora.cuentasB26().isEmpty() ? "" : ", las fichas de Bitácora 26")
                + (hayQrz ? ", QRZ.com" : "") + (hayHamQth ? ", HamQTH" : "");
        int r = JOptionPane.showConfirmDialog(this, pendientes.size() + " contactos tienen algún dato sin rellenar (nombre, QTH o locator).\n\n"
                        + "Se buscarán en: " + fuentes + ".\n"
                        + "Solo se rellena lo que está vacío: lo que ya está escrito no se cambia.\n"
                        + (hayQrz || hayHamQth ? "" : "\n(No tienes cuenta de QRZ.com ni de HamQTH en Servicios Online: solo se usarán tus datos.)\n")
                        + "\n¿Empezar?", "Actualizar contactos", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;

        JDialog dlg = new JDialog(this, "Actualizar contactos", Dialog.ModalityType.APPLICATION_MODAL);
        JProgressBar barra = new JProgressBar(0, pendientes.size());
        barra.setStringPainted(true);
        JLabel lbl = new JLabel("Empezando…");
        JButton bParar = new JButton("Parar");
        final boolean[] parar = {false};
        bParar.addActionListener(e -> { parar[0] = true; bParar.setEnabled(false); lbl.setText("Parando…"); });
        JPanel pp = new JPanel(new BorderLayout(8, 8));
        pp.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        pp.add(lbl, BorderLayout.NORTH);
        pp.add(barra, BorderLayout.CENTER);
        JPanel abajo = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        abajo.add(bParar);
        pp.add(abajo, BorderLayout.SOUTH);
        dlg.setContentPane(pp);
        dlg.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dlg.setSize(460, 150);
        dlg.setLocationRelativeTo(this);

        boolean permisoOtros = !radioclub.syncActivo() || radioclub.tengoPermiso("CONTACTOS");
        String yo = operadorActual == null ? "" : operadorActual;
        int[] cuenta = new int[4];   // 0 completados, 1 sin datos, 2 sin permiso, 3 revisados
        Map<String, String[]> cache = new HashMap<>();
        Thread hilo = new Thread(() -> {
            try (Connection conn = DatabaseManager.conectar();
                 PreparedStatement psUpd = conn.prepareStatement("UPDATE qsos SET nombre = ?, qth = ?, grid = ?, modificado_por = ? WHERE id = ?")) {
                for (int k = 0; k < pendientes.size() && !parar[0]; k++) {
                    Object[] q = pendientes.get(k);
                    int id = (Integer) q[0];
                    String op = (String) q[1], call = (String) q[2], banda = (String) q[3];
                    String nom = (String) q[4], qth = (String) q[5], grid = (String) q[6];
                    boolean esCB = "CB".equalsIgnoreCase(banda.trim());
                    final int paso = k;
                    SwingUtilities.invokeLater(() -> { barra.setValue(paso); if (!parar[0]) lbl.setText("Buscando " + call + "…  (" + (paso + 1) + " de " + pendientes.size() + ")"); });
                    cuenta[3]++;
                    if (!(op.isEmpty() || op.equalsIgnoreCase(yo) || permisoOtros)) { cuenta[2]++; continue; }

                    String[] datos = buscarDatosPropios(conn, call, esCB, id, nom, qth);
                    String[] completo = mezclarDatos(new String[]{nom, qth, grid}, datos);
                    if (faltaAlgo(completo) && cuentaB26 != null && !CuentaBitacora.cuentasB26().isEmpty()) {
                        // Base de datos de Bitácora 26 (en CB solo si no hay duda: un único operador con ese indicativo)
                        List<String[]> fichas = cuentaB26.buscarFichasB26(call, esCB);
                        if (fichas != null && fichas.size() == 1) completo = mezclarDatos(completo, fichas.getFirst());
                    }
                    if (!esCB && faltaAlgo(completo)) {
                        String[] ext = cache.get(call);
                        if (ext == null && !cache.containsKey(call)) {
                            ext = hayQrz ? buscarEnQrzSincrono(call) : null;
                            if (ext == null && hayHamQth) ext = buscarEnHamQth(call);
                            cache.put(call, ext);
                            try { Thread.sleep(250); } catch (InterruptedException ie) { break; }
                        }
                        completo = mezclarDatos(completo, ext);
                    }
                    if (completo[0].equals(nom) && completo[1].equals(qth) && completo[2].equals(grid)) { cuenta[1]++; continue; }
                    psUpd.setString(1, completo[0].toUpperCase());
                    psUpd.setString(2, completo[1].toUpperCase());
                    psUpd.setString(3, completo[2].toUpperCase());
                    psUpd.setString(4, yo);
                    psUpd.setInt(5, id);
                    psUpd.executeUpdate();
                    cuenta[0]++;
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(dlg, "Error al actualizar: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    String msg = "Contactos revisados: " + cuenta[3] + " de " + pendientes.size() + ".\n"
                            + "Completados: " + cuenta[0] + ".\n"
                            + "Sin datos en ningún sitio: " + cuenta[1] + ".";
                    if (cuenta[2] > 0) msg += "\n\n" + cuenta[2] + " son de otros operadores y tu papel no tiene permiso para cambiarlos.";
                    if (parar[0]) msg += "\n\n(Has parado la búsqueda antes de terminar.)";
                    if (radioclub.syncActivo() && cuenta[0] > 0) msg += "\n\nPulsa Sincronizar para que los contactos del radioclub completados lleguen a los demás.";
                    JOptionPane.showMessageDialog(LibroGuardiaUI.this, msg, "Actualizar contactos", JOptionPane.INFORMATION_MESSAGE);
                    cargarQSOsDesdeBaseDatos();
                    subirContactosSinSubir(numeral, vistaRC, null);   // y después, los que falten por subir a los servicios online
                });
            }
        }, "ActualizarContactos");
        hilo.setDaemon(true);
        hilo.start();
        dlg.setVisible(true);
    }

    private static boolean faltaAlgo(String[] d) {
        return d[0].isEmpty() || d[1].isEmpty() || d[2].isEmpty();
    }

    /** Rellena los huecos de "base" con lo que haya en "extra" (sin cambiar lo que ya está). */
    private static String[] mezclarDatos(String[] base, String[] extra) {
        String[] r = {base[0] == null ? "" : base[0], base[1] == null ? "" : base[1], base[2] == null ? "" : base[2]};
        if (extra == null) return r;
        for (int i = 0; i < 3; i++) if (r[i].isEmpty() && extra[i] != null && !extra[i].trim().isEmpty()) r[i] = extra[i].trim();
        return r;
    }

    /**
     * Datos propios de un indicativo: en CB, la lista BANDA CIUDADANA y los contactos de CB (solo si es una sola
     * persona, o la que coincide con el nombre/QTH que ya tiene el contacto); en las demás bandas, otros contactos.
     */
    private String[] buscarDatosPropios(Connection conn, String call, boolean esCB, int idPropio, String nomActual, String qthActual) throws Exception {
        List<String[]> opciones = new ArrayList<>();
        boolean dirCB = false;   // ¿el primero de "opciones" viene de la lista BANDA CIUDADANA?
        if (esCB) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(nombre,''), COALESCE(qth,'') FROM cb_directorio WHERE indicativo = ? AND eliminado = 0")) {
                ps.setString(1, call);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && !rs.getString(1).trim().isEmpty()) { opciones.add(new String[]{rs.getString(1).trim(), rs.getString(2).trim(), ""}); dirCB = true; }
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(nombre,''), COALESCE(qth,''), COALESCE(grid,'') FROM qsos WHERE indicativo = ? AND id <> ? AND "
                + (esCB ? "UPPER(banda) = 'CB'" : "UPPER(COALESCE(banda,'')) <> 'CB'") + " AND TRIM(COALESCE(nombre,'')) <> '' ORDER BY id DESC")) {
            ps.setString(1, call);
            ps.setInt(2, idPropio);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) opciones.add(new String[]{rs.getString(1).trim(), rs.getString(2).trim(), rs.getString(3).trim()});
            }
        }
        if (opciones.isEmpty()) return null;
        // Si el contacto ya tiene nombre o QTH, solo valen los datos de esa misma persona
        List<String[]> validas = new ArrayList<>();
        for (String[] o : opciones) {
            if (!nomActual.isEmpty() && !o[0].equalsIgnoreCase(nomActual)) continue;
            if (!qthActual.isEmpty() && !o[1].isEmpty() && !o[1].equalsIgnoreCase(qthActual)) continue;
            validas.add(o);
        }
        if (validas.isEmpty()) return null;
        // En CB manda la lista BANDA CIUDADANA (es la que se corrige a mano): si el indicativo está en ella, se usa esa
        if (esCB && !opciones.isEmpty() && validas.get(0) == opciones.get(0) && dirCB) return new String[]{validas.get(0)[0], validas.get(0)[1], ""};
        if (esCB) {
            // En CB, el mismo indicativo puede ser de varias personas: si hay dudas, no se rellena
            java.util.Set<String> personas = new java.util.HashSet<>();
            for (String[] o : validas) personas.add(o[0].toUpperCase() + "|" + o[1].toUpperCase());
            if (personas.size() > 1 && nomActual.isEmpty()) {
                java.util.Set<String> nombres = new java.util.HashSet<>();
                for (String[] o : validas) nombres.add(o[0].toUpperCase());
                if (nombres.size() > 1) return null;
            }
        }
        // Se juntan los datos de las opciones válidas (la más reciente primero)
        String[] r = {"", "", ""};
        for (String[] o : validas) r = mezclarDatos(r, o);
        return r;
    }

    private void consultarQrz(String call, String usuarioQrz, String pwdQrz) {
        new Thread(() -> {
            try {
                SwingUtilities.invokeLater(() -> {
                    if (!indicativoActualEs(call)) return;
                    lblEstado.setText("Consultando QRZ.com XML API para " + call + "...");
                });

                try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {

                    if (qrzSessionKey == null || qrzSessionKey.isEmpty()) {
                        String loginUrl = "https://xmldata.qrz.com/xml/current/?username=" + URLEncoder.encode(usuarioQrz, StandardCharsets.UTF_8) +
                                ";password=" + URLEncoder.encode(pwdQrz, StandardCharsets.UTF_8) + ";agent=EA4IXR_LogBook";

                        HttpRequest loginReq = HttpRequest.newBuilder().uri(URI.create(loginUrl)).header("User-Agent", "EA4IXR-LogBook/1.0").build();
                        HttpResponse<String> loginResp = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
                        qrzSessionKey = extraerXmlTag(loginResp.body(), "Key");

                        if (qrzSessionKey.isEmpty()) {
                            String errorMsg = extraerXmlTag(loginResp.body(), "Error");
                            if (errorMsg.isEmpty()) errorMsg = "Credenciales incorrectas";
                            final String finalErr = errorMsg;
                            SwingUtilities.invokeLater(() -> lblEstado.setText("Error QRZ XML: " + finalErr));
                            return;
                        }
                    }

                    String queryUrl = "https://xmldata.qrz.com/xml/current/?s=" + qrzSessionKey + ";callsign=" + URLEncoder.encode(call, StandardCharsets.UTF_8);
                    HttpRequest queryReq = HttpRequest.newBuilder().uri(URI.create(queryUrl)).header("User-Agent", "EA4IXR-LogBook/1.0").build();
                    HttpResponse<String> queryResp = client.send(queryReq, HttpResponse.BodyHandlers.ofString());
                    String xml = queryResp.body();

                    if (xml.contains("Session Timeout") || xml.contains("Invalid session key")) {
                        qrzSessionKey = null;
                        String loginUrl = "https://xmldata.qrz.com/xml/current/?username=" + URLEncoder.encode(usuarioQrz, StandardCharsets.UTF_8) +
                                ";password=" + URLEncoder.encode(pwdQrz, StandardCharsets.UTF_8) + ";agent=EA4IXR_LogBook";
                        HttpRequest loginReq = HttpRequest.newBuilder().uri(URI.create(loginUrl)).header("User-Agent", "EA4IXR-LogBook/1.0").build();
                        HttpResponse<String> loginResp = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
                        qrzSessionKey = extraerXmlTag(loginResp.body(), "Key");

                        queryUrl = "https://xmldata.qrz.com/xml/current/?s=" + qrzSessionKey + ";callsign=" + URLEncoder.encode(call, StandardCharsets.UTF_8);
                        queryReq = HttpRequest.newBuilder().uri(URI.create(queryUrl)).header("User-Agent", "EA4IXR-LogBook/1.0").build();
                        queryResp = client.send(queryReq, HttpResponse.BodyHandlers.ofString());
                        xml = queryResp.body();
                    }

                    final String callDevuelto = extraerXmlTag(xml, "call");
                    final boolean existeNodoCallsign = xml.contains("<Callsign>") || xml.contains("<Callsign ");
                    final boolean coincidenciaExacta = existeNodoCallsign && callDevuelto.equalsIgnoreCase(call);

                    if (!coincidenciaExacta) {
                        // No está en QRZ.com: se prueba en HamQTH (si el operador tiene cuenta puesta)
                        String[] hq = buscarEnHamQth(call);
                        if (hq != null) {
                            rellenarFormularioDesdeServicio(call, hq, "HamQTH");
                            return;
                        }
                        SwingUtilities.invokeLater(() -> {
                            // Respuesta antigua: el usuario ya ha cambiado el indicativo
                            if (!indicativoActualEs(call)) return;
                            lblEstado.setText("El indicativo " + call + " no está registrado en QRZ.com.");
                            if (lblQrzIcon != null) lblQrzIcon.setVisible(false);
                        });
                        return;
                    }

                    final String fname = extraerXmlTag(xml, "fname");
                    final String name = extraerXmlTag(xml, "name");
                    final String nombreApi = (fname + " " + name).trim();
                    final String gridApi = extraerXmlTag(xml, "grid");
                    final String qthApi = extraerXmlTag(xml, "qth");
                    final String addr2Api = extraerXmlTag(xml, "addr2");
                    final String ciudadFinal = !qthApi.isEmpty() ? qthApi : addr2Api;

                    SwingUtilities.invokeLater(() -> {
                        // Respuesta antigua: el usuario ya ha cambiado el indicativo
                        if (!indicativoActualEs(call)) return;
                        if (txtNombre.getText().isEmpty() && !nombreApi.isEmpty()) {
                            txtNombre.setText(nombreApi.toUpperCase());
                        }
                        if (txtQth.getText().isEmpty() && !ciudadFinal.isEmpty()) {
                            txtQth.setText(ciudadFinal.toUpperCase());
                        }
                        if (txtGrid.getText().isEmpty() && !gridApi.isEmpty()) {
                            txtGrid.setText(gridApi.toUpperCase());
                        }
                        if (lblQrzIcon != null) lblQrzIcon.setVisible(true);
                        lblEstado.setText("Datos de " + call + " cargados desde QRZ.com.");
                    });

                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (!indicativoActualEs(call)) return;
                    lblEstado.setText("Error de red al consultar QRZ API.");
                    if (lblQrzIcon != null) lblQrzIcon.setVisible(false);
                });
            }
        }).start();
    }

    private void verificarExistenciaQrz(String call, String usuarioQrz, String pwdQrz) {
        if (call == null || call.trim().isEmpty()) return;
        final String callFinal = call.trim().toUpperCase();

        new Thread(() -> {
            try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
                if (qrzSessionKey == null || qrzSessionKey.isEmpty()) {
                    String loginUrl = "https://xmldata.qrz.com/xml/current/?username=" + URLEncoder.encode(usuarioQrz, StandardCharsets.UTF_8) +
                            ";password=" + URLEncoder.encode(pwdQrz, StandardCharsets.UTF_8) + ";agent=EA4IXR_LogBook";
                    HttpRequest loginReq = HttpRequest.newBuilder().uri(URI.create(loginUrl)).header("User-Agent", "EA4IXR-LogBook/1.0").build();
                    HttpResponse<String> loginResp = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
                    qrzSessionKey = extraerXmlTag(loginResp.body(), "Key");
                    if (qrzSessionKey.isEmpty()) return;
                }

                String queryUrl = "https://xmldata.qrz.com/xml/current/?s=" + qrzSessionKey + ";callsign=" + URLEncoder.encode(callFinal, StandardCharsets.UTF_8);
                HttpRequest queryReq = HttpRequest.newBuilder().uri(URI.create(queryUrl)).header("User-Agent", "EA4IXR-LogBook/1.0").build();
                HttpResponse<String> queryResp = client.send(queryReq, HttpResponse.BodyHandlers.ofString());
                String xml = queryResp.body();

                final String callDevuelto = extraerXmlTag(xml, "call");
                final boolean existeNodoCallsign = xml.contains("<Callsign>") || xml.contains("<Callsign ");
                final boolean coincidenciaExacta = existeNodoCallsign && callDevuelto.equalsIgnoreCase(callFinal);

                SwingUtilities.invokeLater(() -> {
                    if (!txtIndicativo.getText().trim().equalsIgnoreCase(callFinal)) return;
                    if ("CB".equalsIgnoreCase(String.valueOf(cbBanda.getSelectedItem()))) return;
                    if (lblQrzIcon != null) lblQrzIcon.setVisible(coincidenciaExacta);
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    /** true si el indicativo escrito ahora en el formulario sigue siendo el de la consulta. */
    private boolean indicativoActualEs(String call) {
        if (cbBanda != null && "CB".equalsIgnoreCase(String.valueOf(cbBanda.getSelectedItem()))) return false;
        return txtIndicativo != null && txtIndicativo.getText().trim().equalsIgnoreCase(call);
    }

    private String extraerXmlTag(String xml, String tag) {
        try {
            Pattern pattern = Pattern.compile("<" + tag + "[^>]*>([^<]+)</" + tag + ">", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(xml);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    JButton btnDigitales;

    /** Mientras el programa de DIGITALES (WSJT-X) esté abierto, el botón tiene las letras en verde. Se mira cada 2 segundos. */
    void vigilarProgramaDigitales() {
        Thread t = new Thread(() -> {
            Boolean antes = null;
            while (true) {
                boolean abierto = false;
                try {
                    String guardado = ConfigApp.get("DIGITALES_PROGRAMA", "").trim();
                    String nombre = (guardado.isEmpty() ? "wsjtx.exe" : new File(guardado).getName()).toLowerCase();
                    abierto = ProcessHandle.allProcesses().anyMatch(ph -> ph.info().command()
                            .map(c -> new File(c).getName().toLowerCase().equals(nombre)).orElse(false));
                } catch (Exception ignored) { }
                if (antes == null || antes != abierto) {
                    antes = abierto;
                    final boolean verde = abierto;
                    SwingUtilities.invokeLater(() -> {
                        if (btnDigitales == null) return;
                        btnDigitales.setForeground(verde ? cat.VERDE_ENCENDIDO : UIManager.getColor("Button.foreground"));
                        btnDigitales.repaint();
                    });
                }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }, "VigilarDigitales");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Botón DIGITALES: abre el programa de modos digitales (WSJT-X). La ruta se guarda en DIGITALES_PROGRAMA;
     * si no hay, se busca donde lo instala WSJT-X y, si no está, se pregunta. Si ya está abierto, solo avisa.
     */
    void abrirProgramaDigitales(boolean elegirOtro) {
        File exe = null;
        String guardado = ConfigApp.get("DIGITALES_PROGRAMA", "").trim();
        if (!elegirOtro && !guardado.isEmpty() && new File(guardado).isFile()) exe = new File(guardado);
        if (!elegirOtro && exe == null) {
            java.util.List<String> sitios = new java.util.ArrayList<>();
            sitios.add("C:\\WSJT\\wsjtx\\bin\\wsjtx.exe");
            for (String v : new String[]{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
                String base = System.getenv(v);
                if (base != null) sitios.add(base + "\\wsjtx\\bin\\wsjtx.exe");
            }
            for (String c : sitios) if (new File(c).isFile()) { exe = new File(c); break; }
        }
        if (exe == null) {
            JFileChooser fc = new JFileChooser(guardado.isEmpty() ? null : new File(guardado).getParentFile());
            fc.setDialogTitle("Elige el programa de modos digitales (por ejemplo wsjtx.exe)");
            fc.setFileFilter(new FileNameExtensionFilter("Programas (*.exe)", "exe"));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            exe = fc.getSelectedFile();
            if (elegirOtro) {
                ConfigApp.put("DIGITALES_PROGRAMA", exe.getAbsolutePath());
                lblEstado.setText("DIGITALES: con el botón derecho se abrirá " + exe.getName());
                return;
            }
        }
        ConfigApp.put("DIGITALES_PROGRAMA", exe.getAbsolutePath());
        final File programa = exe;
        new Thread(() -> {
            // ¿Ya está abierto? (así no se abre dos veces)
            boolean abierto = false;
            try {
                Process t = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq " + programa.getName(), "/NH").redirectErrorStream(true).start();
                String salida = new String(t.getInputStream().readAllBytes());
                t.waitFor();
                abierto = salida.toLowerCase().contains(programa.getName().toLowerCase());
            } catch (Exception ignored) { }
            if (abierto) {
                SwingUtilities.invokeLater(() -> lblEstado.setText("DIGITALES: " + programa.getName() + " ya está abierto"));
                return;
            }
            try {
                new ProcessBuilder(programa.getAbsolutePath()).directory(programa.getParentFile()).start();
                SwingUtilities.invokeLater(() -> lblEstado.setText("DIGITALES: abriendo " + programa.getName() + "…"));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "No se pudo abrir " + programa.getAbsolutePath() + ":\n" + ex.getMessage()
                                + "\n\nCon el botón derecho en DIGITALES puedes elegir el programa.", "DIGITALES", JOptionPane.ERROR_MESSAGE));
            }
        }, "AbrirDigitales").start();
    }

    /**
     * Botón "QRZ.com" dibujado a mano (vector: se ve nítido a cualquier tamaño): pastilla azul con degradado,
     * una lupa y el texto "QRZ.com". Al pasar el ratón se ilumina y el borde se pone ámbar.
     */
    static class IconoBotonQrz implements Icon {
        boolean encima, pulsado;
        private static final int ALTO = 24;
        private static final Font LETRA = new Font("Arial", Font.BOLD, 13);

        private int ancho() {
            java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = im.createGraphics();
            int w = g.getFontMetrics(LETRA).stringWidth("QRZ.com");
            g.dispose();
            return 30 + w + 12;
        }
        @Override public int getIconWidth() { return ancho(); }
        @Override public int getIconHeight() { return ALTO; }

        @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            int w = ancho() - 1, h = ALTO - 1;
            if (pulsado) g.translate(0, 1);
            // Fondo: degradado azul (más claro con el ratón encima)
            Color arriba = encima ? new Color(70, 140, 235) : new Color(45, 105, 200);
            Color abajo = encima ? new Color(30, 80, 170) : new Color(20, 60, 135);
            g.setPaint(new GradientPaint(x, y, arriba, x, y + h, abajo));
            g.fill(new java.awt.geom.RoundRectangle2D.Float(x, y, w, h, h, h));
            // Brillo arriba
            g.setPaint(new GradientPaint(x, y, new Color(255, 255, 255, 70), x, y + h / 2f, new Color(255, 255, 255, 0)));
            g.fill(new java.awt.geom.RoundRectangle2D.Float(x + 1, y + 1, w - 2, h / 2f, h - 2, h - 2));
            // Borde (ámbar con el ratón encima)
            g.setStroke(new BasicStroke(1.2f));
            g.setColor(encima ? TEMA_AMBAR : new Color(120, 170, 240));
            g.draw(new java.awt.geom.RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, h - 1, h - 1));
            // Lupa
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float cx = x + 13, cy = y + 10.5f, r = 4.5f;
            g.draw(new java.awt.geom.Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
            g.draw(new java.awt.geom.Line2D.Float(cx + 3.3f, cy + 3.3f, cx + 7f, cy + 7f));
            // Texto
            g.setFont(LETRA);
            FontMetrics fm = g.getFontMetrics();
            int ty = y + (ALTO - fm.getHeight()) / 2 + fm.getAscent();
            g.setColor(new Color(0, 0, 0, 90));
            g.drawString("QRZ.com", x + 29, ty + 1);
            g.setColor(Color.WHITE);
            g.drawString("QRZ.com", x + 28, ty);
            g.dispose();
        }
    }

    // Abre en el navegador la página web del radioclub (Configurar Radioclub → Página web). Si no hay, no hace nada.
    private void abrirWebRadioclub() {
        String web = ConfigApp.get("RADIOCLUB_WEB", "").trim();
        if (web.isEmpty()) return;
        if (!web.toLowerCase().startsWith("http://") && !web.toLowerCase().startsWith("https://")) web = "https://" + web;
        try {
            Desktop.getDesktop().browse(new URI(web.replace(" ", "%20")));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "No se pudo abrir la web del radioclub (" + web + "): " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void abrirPaginaQrz() {
        String call = txtIndicativo.getText().trim();
        if (call.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Introduce un indicativo primero.", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            String url = "https://www.qrz.com/db/" + URLEncoder.encode(call, StandardCharsets.UTF_8);
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "No se pudo abrir el navegador: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Opciones de exportación: operador (null = todos) y bandas (null o vacío = todas). */
    private record OpcionesExportAdi(String operador, List<String> bandas) {}

    /** Pregunta qué se quiere exportar. Devuelve null si se cancela. */
    private OpcionesExportAdi pedirOpcionesExportacionAdi(boolean esActividad) {
        List<String> operadores = obtenerListaOperadores();
        if (operadorActual != null && !operadorActual.isEmpty() && !operadores.contains(operadorActual)) {
            operadores.add(0, operadorActual);
        }
        JComboBox<String> cbOperador = new JComboBox<>(operadores.toArray(new String[0]));
        if (operadorActual != null && !operadorActual.isEmpty()) cbOperador.setSelectedItem(operadorActual);
        cbOperador.setMaximumSize(new Dimension(300, cbOperador.getPreferredSize().height));

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        if (esActividad) {
            boolean deRadioclub = radioclub.esVistaRadioclubSeleccionada();
            JRadioButton rbCompleta = new JRadioButton(deRadioclub
                    ? "Todo el " + NOMBRE_VISTA_RC + " (todos los operadores)"
                    : "Actividad completa (todos los contactos)", true);
            JRadioButton rbOperador = new JRadioButton("Solo los contactos de un operador:");
            ButtonGroup grupo = new ButtonGroup();
            grupo.add(rbCompleta);
            grupo.add(rbOperador);
            cbOperador.setEnabled(false);
            java.awt.event.ActionListener alternar = e -> cbOperador.setEnabled(rbOperador.isSelected());
            rbCompleta.addActionListener(alternar);
            rbOperador.addActionListener(alternar);

            panel.add(rbCompleta);
            panel.add(rbOperador);
            panel.add(cbOperador);
            for (Component comp : panel.getComponents()) ((JComponent) comp).setAlignmentX(Component.LEFT_ALIGNMENT);

            int resp = JOptionPane.showConfirmDialog(this, panel, deRadioclub ? "Exportar " + NOMBRE_VISTA_RC : "Exportar actividad",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (resp != JOptionPane.OK_OPTION) return null;

            if (rbOperador.isSelected()) {
                Object sel = cbOperador.getSelectedItem();
                if (sel == null || sel.toString().isEmpty()) {
                    JOptionPane.showMessageDialog(this, "No hay ningún operador seleccionado.",
                            "Aviso", JOptionPane.WARNING_MESSAGE);
                    return null;
                }
                return new OpcionesExportAdi(sel.toString(), null);
            }
            return new OpcionesExportAdi(null, null);
        }

        // LOG Principal: log de un operador, todo o solo algunas bandas
        JRadioButton rbTodo = new JRadioButton("Todo el log del operador", true);
        JRadioButton rbBandas = new JRadioButton("Solo estas bandas:");
        ButtonGroup grupo = new ButtonGroup();
        grupo.add(rbTodo);
        grupo.add(rbBandas);

        JPanel panelBandas = new JPanel(new GridLayout(0, 4));
        List<JCheckBox> checks = new ArrayList<>();
        for (String banda : BANDAS) {
            if (banda.isEmpty()) continue;
            JCheckBox chk = new JCheckBox(banda);
            chk.setEnabled(false);
            checks.add(chk);
            panelBandas.add(chk);
        }
        java.awt.event.ActionListener alternarBandas = e -> {
            for (JCheckBox chk : checks) chk.setEnabled(rbBandas.isSelected());
        };
        rbTodo.addActionListener(alternarBandas);
        rbBandas.addActionListener(alternarBandas);

        panel.add(new JLabel("Operador:"));
        panel.add(cbOperador);
        panel.add(Box.createVerticalStrut(8));
        panel.add(rbTodo);
        panel.add(rbBandas);
        panel.add(panelBandas);
        for (Component comp : panel.getComponents()) ((JComponent) comp).setAlignmentX(Component.LEFT_ALIGNMENT);

        int resp = JOptionPane.showConfirmDialog(this, panel, "Exportar log del operador",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (resp != JOptionPane.OK_OPTION) return null;

        Object selOp = cbOperador.getSelectedItem();
        if (selOp == null || selOp.toString().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No hay ningún operador seleccionado.",
                    "Aviso", JOptionPane.WARNING_MESSAGE);
            return null;
        }

        List<String> bandasElegidas = null;
        if (rbBandas.isSelected()) {
            bandasElegidas = new ArrayList<>();
            for (JCheckBox chk : checks) {
                if (chk.isSelected()) bandasElegidas.add(chk.getText());
            }
            if (bandasElegidas.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Marca al menos una banda.",
                        "Aviso", JOptionPane.WARNING_MESSAGE);
                return null;
            }
        }
        return new OpcionesExportAdi(selOp.toString(), bandasElegidas);
    }

    /** Importa a la lista BANDA CIUDADANA un fichero ADI (solo registros de CB / 11m, o sin banda). */
    private void importarListaBandaCiudadanaAdi() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Importar a la lista BANDA CIUDADANA desde ADIF");
        fc.setFileFilter(new FileNameExtensionFilter("Ficheros ADIF (*.adi, *.adif)", "adi", "adif"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        int nuevos = 0, cambiados = 0, ignorados = 0;
        try {
            String texto = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            int eoh = texto.toUpperCase().indexOf("<EOH>");
            if (eoh >= 0) texto = texto.substring(eoh + 5);
            java.util.regex.Pattern campo = java.util.regex.Pattern.compile("<([A-Za-z0-9_]+):(\\d+)(?::[A-Za-z])?>");
            try (Connection conn = DatabaseManager.conectar()) {
                for (String registro : texto.split("(?i)<EOR>")) {
                    Map<String, String> c = new HashMap<>();
                    java.util.regex.Matcher m = campo.matcher(registro);
                    while (m.find()) {
                        int n = Integer.parseInt(m.group(2));
                        int ini = m.end();
                        c.put(m.group(1).toUpperCase(), registro.substring(ini, Math.min(registro.length(), ini + n)));
                    }
                    String call = txtCB(c.getOrDefault("CALL", ""));
                    if (call.isEmpty()) continue;
                    String banda = c.getOrDefault("BAND", "").trim().toUpperCase();
                    if (!banda.isEmpty() && !banda.equals("11M") && !banda.equals("CB")) { ignorados++; continue; }
                    String nom = txtCB(c.getOrDefault("NAME", "")), qth = txtCB(c.getOrDefault("QTH", ""));
                    String[] antes = null;
                    try (PreparedStatement ps = conn.prepareStatement("SELECT nombre, qth, eliminado FROM cb_directorio WHERE indicativo = ?")) {
                        ps.setString(1, call);
                        try (ResultSet rs = ps.executeQuery()) { if (rs.next()) antes = new String[]{txtCB(rs.getString(1)), txtCB(rs.getString(2)), String.valueOf(rs.getInt(3))}; }
                    }
                    if (antes == null || "1".equals(antes[2])) {
                        upsertIndicativoCB(conn, call, nom, qth);
                        nuevos++;
                    } else {
                        String nn = nom.isEmpty() ? antes[0] : nom, qq = qth.isEmpty() ? antes[1] : qth;
                        if ((!nn.equals(antes[0]) || !qq.equals(antes[1])) && radioclub.tengoPermiso("LISTACB")) {   // sin permiso solo se añaden los nuevos
                            upsertIndicativoCB(conn, call, nn, qq);
                            cambiados++;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "No se pudo importar: " + ex.getMessage(), "Importar ADI", JOptionPane.ERROR_MESSAGE);
            return;
        }
        cargarVistaBandaCiudadana();
        aplicarFiltroCB();
        JOptionPane.showMessageDialog(this, "Importación terminada.\n\nIndicativos nuevos: " + nuevos + "\nIndicativos actualizados: " + cambiados
                        + (ignorados > 0 ? "\nContactos de otras bandas (no se importan): " + ignorados : ""),
                "Importar ADI", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Exporta la lista BANDA CIUDADANA a un fichero ADI: un registro por indicativo (indicativo, nombre y QTH, banda 11m). */
    private void exportarListaBandaCiudadanaAdi() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Exportar la lista BANDA CIUDADANA a ADIF");
        fileChooser.setFileFilter(new FileNameExtensionFilter("Ficheros ADIF (*.adi)", "adi"));
        fileChooser.setSelectedFile(new File("banda_ciudadana.adi"));
        if (fileChooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File destino = fileChooser.getSelectedFile();
        if (!destino.getName().toLowerCase().endsWith(".adi")) destino = new File(destino.getAbsolutePath() + ".adi");
        int contador = 0;
        try (Connection conn = DatabaseManager.conectar();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT indicativo, nombre, qth FROM cb_directorio WHERE eliminado = 0 ORDER BY indicativo ASC");
             java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(destino), StandardCharsets.UTF_8)) {
            w.write("Lista BANDA CIUDADANA exportada por " + NOMBRE_VISIBLE + " EA4IXR\n");
            w.write("<ADIF_VER:5>3.1.4\n");
            w.write("<EOH>\n\n");
            while (rs.next()) {
                String call = txtCB(rs.getString(1)), nombre = txtCB(rs.getString(2)), qth = txtCB(rs.getString(3));
                if (call.isEmpty()) continue;
                w.write("<CALL:" + call.length() + ">" + call + " ");
                w.write("<BAND:3>11m ");
                if (!nombre.isEmpty()) w.write("<NAME:" + nombre.length() + ">" + nombre + " ");
                if (!qth.isEmpty()) w.write("<QTH:" + qth.length() + ">" + qth + " ");
                w.write("<EOR>\n");
                contador++;
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "No se pudo exportar la lista: " + ex.getMessage(), "Exportar ADI", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(this, "Exportados " + contador + " indicativos de la lista BANDA CIUDADANA a:\n" + destino.getAbsolutePath(),
                "Exportar ADI", JOptionPane.INFORMATION_MESSAGE);
    }

    private void exportarFicheroAdi() {
        if (esVistaBandaCiudadanaSeleccionada()) {
            exportarListaBandaCiudadanaAdi();
            return;
        }
        String actividadSeleccionada = (cbSelectorActividad != null) ? (String) cbSelectorActividad.getSelectedItem() : null;
        // RADIOCLUB: desde el LOG Radioclub se exporta su log (completo o de un operador)
        boolean exportarRadioclub = radioclub.esVistaRadioclubSeleccionada();
        boolean filtrarPorActividad = !exportarRadioclub && (actividadSeleccionada != null && !actividadSeleccionada.equals("LOG Principal") && !actividadSeleccionada.startsWith("---"));

        // Pregunta qué exportar (actividad completa / un operador / bandas concretas)
        OpcionesExportAdi opciones = pedirOpcionesExportacionAdi(filtrarPorActividad || exportarRadioclub);
        if (opciones == null) return;

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Exportar " + NOMBRE_VISIBLE + " a ADIF");
        fileChooser.setFileFilter(new FileNameExtensionFilter("Ficheros ADIF (*.adi)", "adi"));
        fileChooser.setSelectedFile(new File("libro_guardia_ea4ixr.adi"));

        int seleccion = fileChooser.showSaveDialog(this);
        if (seleccion == JFileChooser.APPROVE_OPTION) {
            File archivoDestino = fileChooser.getSelectedFile();
            if (!archivoDestino.getName().toLowerCase().endsWith(".adi")) {
                archivoDestino = new File(archivoDestino.getAbsolutePath() + ".adi");
            }

            int numeralExport = filtrarPorActividad ? obtenerNumeralDeActividadDesdeSeleccion() : 0;

            StringBuilder sqlSb = new StringBuilder(
                    "SELECT fecha, hora, indicativo, banda, modo, freq_tx, freq_rx, rst_sent, rst_rcvd, nombre, qth, grid, actividad, operador, estacion, COALESCE(comentario, '') AS comentario FROM qsos WHERE ");
            List<Object> parametros = new ArrayList<>();
            if (filtrarPorActividad) {
                if (numeralExport > 0) {
                    sqlSb.append("actividad_numeral = ?");
                    parametros.add(numeralExport);
                } else {
                    sqlSb.append("actividad = ?");
                    parametros.add(actividadSeleccionada);
                }
            } else if (exportarRadioclub) {
                sqlSb.append("radioclub = 1");
            } else {
                sqlSb.append("1 = 1");
            }
            if (opciones.operador() != null) {
                sqlSb.append(" AND operador = ?");
                parametros.add(opciones.operador());
            }
            if (opciones.bandas() != null && !opciones.bandas().isEmpty()) {
                sqlSb.append(" AND UPPER(banda) IN (");
                for (int i = 0; i < opciones.bandas().size(); i++) {
                    sqlSb.append(i == 0 ? "?" : ", ?");
                    parametros.add(opciones.bandas().get(i).toUpperCase());
                }
                sqlSb.append(")");
            }
            String sql = sqlSb.toString();

            try (Connection conn = DatabaseManager.conectar();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                for (int i = 0; i < parametros.size(); i++) {
                    Object valor = parametros.get(i);
                    if (valor instanceof Integer n) {
                        pstmt.setInt(i + 1, n);
                    } else {
                        pstmt.setString(i + 1, String.valueOf(valor));
                    }
                }

                try (ResultSet rs = pstmt.executeQuery();
                     FileWriter writer = new FileWriter(archivoDestino)) {

                    writer.write("ADIF Export generated by Bitacora 26 EA4IXR\n");
                    writer.write("<ADIF_VER:5>3.1.4\n");
                    writer.write("<EOH>\n\n");

                    int contador = 0;
                    while (rs.next()) {
                        String call = rs.getString("indicativo");
                        String fechaRaw = rs.getString("fecha");
                        String horaRaw = rs.getString("hora");
                        String banda = rs.getString("banda");
                        String modo = rs.getString("modo");
                        String freqTx = rs.getString("freq_tx");
                        String rstS = rs.getString("rst_sent");
                        String rstR = rs.getString("rst_rcvd");
                        String nombre = rs.getString("nombre");
                        String qth = rs.getString("qth");
                        String grid = rs.getString("grid");
                        String actividad = rs.getString("actividad");

                        String fechaAdi = "";
                        if (fechaRaw != null && fechaRaw.contains("/")) {
                            String[] partes = fechaRaw.split("/");
                            if (partes.length == 3) {
                                fechaAdi = partes[2] + partes[1] + partes[0];
                            }
                        }

                        String horaAdi = "";
                        if (horaRaw != null) {
                            horaAdi = horaRaw.replace(":", "") + "00";
                            if (horaAdi.length() > 6) horaAdi = horaAdi.substring(0, 6);
                        }

                        if (call != null && !call.isEmpty()) writer.write("<CALL:" + call.length() + ">" + call + " ");
                        if (!fechaAdi.isEmpty()) writer.write("<QSO_DATE:8>" + fechaAdi + " ");
                        if (!horaAdi.isEmpty()) writer.write("<TIME_ON:" + horaAdi.length() + ">" + horaAdi + " ");
                        if (banda != null && !banda.isEmpty())
                            writer.write("<BAND:" + banda.length() + ">" + banda + " ");
                        if ("FT4".equalsIgnoreCase(modo)) writer.write("<MODE:4>MFSK <SUBMODE:3>FT4 ");   // FT4 en ADIF
                        else if (modo != null && !modo.isEmpty()) writer.write("<MODE:" + modo.length() + ">" + modo + " ");
                        if (freqTx != null && !freqTx.isEmpty())
                            writer.write("<FREQ:" + freqTx.length() + ">" + freqTx + " ");
                        if (rstS != null && !rstS.isEmpty())
                            writer.write("<RST_SENT:" + rstS.length() + ">" + rstS + " ");
                        if (rstR != null && !rstR.isEmpty())
                            writer.write("<RST_RCVD:" + rstR.length() + ">" + rstR + " ");
                        if (nombre != null && !nombre.isEmpty())
                            writer.write("<NAME:" + nombre.length() + ">" + nombre + " ");
                        if (qth != null && !qth.isEmpty()) writer.write("<QTH:" + qth.length() + ">" + qth + " ");
                        if (grid != null && !grid.isEmpty())
                            writer.write("<GRIDSQUARE:" + grid.length() + ">" + grid + " ");
                        if (actividad != null && !actividad.isEmpty())
                            writer.write("<MY_SOTA_REF:" + actividad.length() + ">" + actividad + " ");

                        String operadorRow = rs.getString("operador");
                        if (operadorRow != null && !operadorRow.isEmpty())
                            writer.write("<OPERATOR:" + operadorRow.length() + ">" + operadorRow + " ");
                        String estacionRow = rs.getString("estacion");
                        if (estacionRow != null && !estacionRow.isEmpty())
                            writer.write("<STATION_CALLSIGN:" + estacionRow.length() + ">" + estacionRow + " ");
                        String comentarioRow = rs.getString("comentario").replace('\r', ' ').replace('\n', ' ').trim();
                        if (!comentarioRow.isEmpty())
                            writer.write("<COMMENT:" + comentarioRow.length() + ">" + comentarioRow + " ");

                        writer.write("<EOR>\n");
                        contador++;
                    }

                    JOptionPane.showMessageDialog(this, "Se han exportado " + contador + " QSOs correctamente a:\n" + archivoDestino.getAbsolutePath(), "Exportación Exitosa", JOptionPane.INFORMATION_MESSAGE);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error al exportar el fichero ADIF: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** Un contacto leído de un fichero ADI, ya normalizado. */
    private record QsoImportado(String fecha, String hora, String indicativo, String banda, String modo,
                                String freq, String rstSent, String rstRcvd, String nombre, String qth,
                                String grid, String operador, String estacion, String comentario) {}

    /** Lo que el usuario elige al importar: actividad nueva o log principal de un operador. */
    private record OpcionesImportAdi(boolean comoActividad, String nombreActividad, String operador) {}

    /** Devuelve la opción de la lista que coincide sin distinguir mayúsculas ("40M" -> "40m"). */
    String normalizarOpcion(String valor, String[] lista) {
        String v = valor.trim();
        for (String opcion : lista) {
            if (!opcion.isEmpty() && opcion.equalsIgnoreCase(v)) return opcion;
        }
        return v.toUpperCase();
    }

    private String sugerirNombreActividadDesdeFichero(File fichero, String refActividad) {
        String base = null;
        if (refActividad != null && !refActividad.trim().isEmpty()) {
            base = refActividad.replaceAll("\\s*#\\d+$", "");
        }
        if (base == null || base.trim().isEmpty()) {
            base = fichero.getName();
            int punto = base.lastIndexOf('.');
            if (punto > 0) base = base.substring(0, punto);
            base = base.replaceFirst("(?i)^actividad_\\d+_-_", "").replace('_', ' ');
        }
        return base.trim().toUpperCase();
    }

    /** Pregunta cómo importar. Devuelve null si se cancela. */
    private OpcionesImportAdi pedirOpcionesImportacionAdi(int total, String nombreSugerido) {
        List<String> operadores = obtenerListaOperadores();
        if (operadorActual != null && !operadorActual.isEmpty() && !operadores.contains(operadorActual)) {
            operadores.add(0, operadorActual);
        }
        JComboBox<String> cbOperador = new JComboBox<>(operadores.toArray(new String[0]));
        if (operadorActual != null && !operadorActual.isEmpty()) cbOperador.setSelectedItem(operadorActual);
        cbOperador.setMaximumSize(new Dimension(300, cbOperador.getPreferredSize().height));

        JTextField txtNombre = new JTextField(25);
        aplicarFiltroMayusculas(txtNombre);
        txtNombre.setText(nombreSugerido);
        txtNombre.setMaximumSize(new Dimension(380, txtNombre.getPreferredSize().height));

        JRadioButton rbActividad = new JRadioButton("Importar como una actividad nueva (con su log)", true);
        JRadioButton rbLog = new JRadioButton("Importar al log principal de un operador");
        ButtonGroup grupo = new ButtonGroup();
        grupo.add(rbActividad);
        grupo.add(rbLog);
        cbOperador.setEnabled(false);
        java.awt.event.ActionListener alternar = e -> {
            txtNombre.setEnabled(rbActividad.isSelected());
            cbOperador.setEnabled(rbLog.isSelected());
        };
        rbActividad.addActionListener(alternar);
        rbLog.addActionListener(alternar);

        JLabel nota = new JLabel("<html>En una actividad, cada contacto conserva el operador que lo hizo.<br>"
                + "En el log principal solo se añaden los contactos del operador elegido<br>"
                + "(y los que el fichero no asigna a ningún operador).</html>");
        nota.setForeground(new Color(150, 170, 200));

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(new JLabel("El fichero contiene " + total + " contactos. ¿Cómo quieres importarlos?"));
        panel.add(Box.createVerticalStrut(8));
        panel.add(rbActividad);
        panel.add(new JLabel("Nombre de la actividad:"));
        panel.add(txtNombre);
        panel.add(Box.createVerticalStrut(8));
        panel.add(rbLog);
        panel.add(Box.createVerticalStrut(8));
        panel.add(new JLabel("Operador (solo para el log principal):"));
        panel.add(cbOperador);
        panel.add(Box.createVerticalStrut(8));
        panel.add(nota);
        for (Component comp : panel.getComponents()) ((JComponent) comp).setAlignmentX(Component.LEFT_ALIGNMENT);

        int resp = JOptionPane.showConfirmDialog(this, panel, "Importar ADI",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (resp != JOptionPane.OK_OPTION) return null;

        Object selOp = cbOperador.getSelectedItem();
        String operador = (selOp == null) ? "" : selOp.toString().trim().toUpperCase();
        if (rbLog.isSelected() && operador.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No hay ningún operador seleccionado.",
                    "Aviso", JOptionPane.WARNING_MESSAGE);
            return null;
        }

        String nombreActividad = txtNombre.getText().trim().toUpperCase();
        if (rbActividad.isSelected() && nombreActividad.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Introduce un nombre para la actividad.",
                    "Aviso", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return new OpcionesImportAdi(rbActividad.isSelected(), nombreActividad, operador);
    }

    private void importarFicheroAdi() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Importar Fichero ADIF (.adi)");
        fileChooser.setFileFilter(new FileNameExtensionFilter("Ficheros ADIF (*.adi)", "adi"));

        int seleccion = fileChooser.showOpenDialog(this);
        if (seleccion != JFileChooser.APPROVE_OPTION) return;
        File archivoOrigen = fileChooser.getSelectedFile();

        // 1) Leer el fichero completo
        List<QsoImportado> lista = new ArrayList<>();
        String refActividad = null;
        String bandaComun = null;
        String modoComun = null;
        boolean bandaVariable = false;
        boolean modoVariable = false;

        try (BufferedReader reader = new BufferedReader(new FileReader(archivoOrigen))) {
            StringBuilder contenido = new StringBuilder();
            String linea;
            while ((linea = reader.readLine()) != null) {
                contenido.append(linea).append("\n");
            }

            String[] registros = contenido.toString().split("(?i)<EOR>");

            for (String reg : registros) {
                if (!reg.toUpperCase().contains("<CALL:")) continue;   // QRZ.com y otros escriben las etiquetas en minúsculas

                String callsign = extraerCampoAdi(reg, "CALL");
                String fechaAdi = extraerCampoAdi(reg, "QSO_DATE");
                String horaAdi = extraerCampoAdi(reg, "TIME_ON");
                String banda = extraerCampoAdi(reg, "BAND");
                String modo = extraerCampoAdi(reg, "MODE");
                String subModo = extraerCampoAdi(reg, "SUBMODE");
                if (modo != null && modo.equalsIgnoreCase("MFSK") && subModo != null && subModo.equalsIgnoreCase("FT4")) modo = "FT4";
                String freq = extraerCampoAdi(reg, "FREQ");
                String rstS = extraerCampoAdi(reg, "RST_SENT");
                String rstR = extraerCampoAdi(reg, "RST_RCVD");
                String nombre = extraerCampoAdi(reg, "NAME");
                String qth = extraerCampoAdi(reg, "QTH");
                String grid = extraerCampoAdi(reg, "GRIDSQUARE");
                if (grid == null) grid = extraerCampoAdi(reg, "GRID");
                String operadorFichero = extraerCampoAdi(reg, "OPERATOR");
                String estacionFichero = extraerCampoAdi(reg, "STATION_CALLSIGN");   // indicativo con el que se emitió
                String comentarioFichero = extraerCampoAdi(reg, "COMMENT");

                if (callsign == null || callsign.isEmpty()) continue;
                if (refActividad == null) refActividad = extraerCampoAdi(reg, "MY_SOTA_REF");

                String fechaFormateada = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                if (fechaAdi != null && fechaAdi.length() == 8) {
                    fechaFormateada = fechaAdi.substring(6, 8) + "/" + fechaAdi.substring(4, 6) + "/" + fechaAdi.substring(0, 4);
                }

                String horaFormateada = LocalTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("HH:mm"));
                if (horaAdi != null && horaAdi.length() >= 4) {
                    horaFormateada = horaAdi.substring(0, 2) + ":" + horaAdi.substring(2, 4);
                }

                // Banda y modo comunes a todos los contactos (para configurar la actividad importada)
                if (banda == null) {
                    bandaVariable = true;
                } else {
                    String bn = normalizarOpcion(banda, BANDAS);
                    if (bandaComun == null) bandaComun = bn;
                    else if (!bandaComun.equalsIgnoreCase(bn)) bandaVariable = true;
                }
                if (modo == null) {
                    modoVariable = true;
                } else {
                    String mn = normalizarOpcion(modo, MODOS);
                    if (modoComun == null) modoComun = mn;
                    else if (!modoComun.equalsIgnoreCase(mn)) modoVariable = true;
                }

                if (banda == null) banda = "CB";
                if (modo == null) modo = "USB";
                if (freq == null) {
                    InfoBanda infoBandaImp = CONFIG_BANDAS.get(normalizarOpcion(banda, BANDAS));
                    freq = (infoBandaImp != null) ? infoBandaImp.freq : "27.275";
                }
                if (rstS == null) rstS = "59";
                if (rstR == null) rstR = "59";
                if (nombre == null) nombre = "";
                if (qth == null) qth = "";
                if (grid == null) grid = "";
                if (operadorFichero == null) operadorFichero = "";
                if (estacionFichero == null) estacionFichero = "";

                lista.add(new QsoImportado(fechaFormateada, horaFormateada, callsign.toUpperCase(),
                        normalizarOpcion(banda, BANDAS), normalizarOpcion(modo, MODOS), freq, rstS, rstR,
                        nombre.toUpperCase(), qth.toUpperCase(), grid.toUpperCase(),
                        operadorFichero.trim().toUpperCase(), estacionFichero.trim().toUpperCase(),
                        comentarioFichero == null ? "" : comentarioFichero.trim().toUpperCase()));
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Error al leer el fichero ADIF: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (lista.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No se ha encontrado ningún contacto en el fichero.",
                    "Importar ADI", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 2) Preguntar cómo importar
        OpcionesImportAdi opciones = pedirOpcionesImportacionAdi(lista.size(),
                sugerirNombreActividadDesdeFichero(archivoOrigen, refActividad));
        if (opciones == null) return;

        // 3) Guardar todo en una sola operación (si algo falla, no se importa nada)
        int importados = 0;
        int omitidos = 0;
        int otroOperador = 0;
        int sinOperador = 0;
        int nuevoNumeral = 0;
        String nombreVisible = opciones.nombreActividad();
        // Si todos los contactos comparten banda y modo, la actividad queda configurada con ellos
        String bandaActividad = (bandaVariable || bandaComun == null) ? "" : bandaComun.toUpperCase();
        String modoActividad = (modoVariable || modoComun == null) ? "" : modoComun.toUpperCase();

        try (Connection conn = DatabaseManager.conectar()) {
            conn.setAutoCommit(false);
            try {
                String nombreInterno = "";
                if (opciones.comoActividad()) {
                    nuevoNumeral = 1;
                    try (Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(numeral), 0) FROM actividades")) {
                        if (rs.next()) nuevoNumeral = rs.getInt(1) + 1;
                    }
                    soltarContactosHuerfanos(conn, nuevoNumeral);
                    nombreInterno = nombreVisible + " #" + nuevoNumeral;

                    String sqlAct = "INSERT INTO actividades (nombre, tipo, numeral, fecha_creacion, banda_simple, modo_simple) "
                            + "VALUES (?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement psAct = conn.prepareStatement(sqlAct)) {
                        psAct.setString(1, nombreInterno);
                        psAct.setString(2, "SIMPLE");
                        psAct.setInt(3, nuevoNumeral);
                        psAct.setString(4, LocalDate.now(ZoneOffset.UTC).toString());
                        psAct.setString(5, bandaActividad);
                        psAct.setString(6, modoActividad);
                        psAct.executeUpdate();
                    }
                }

                String sqlDup = "SELECT 1 FROM qsos WHERE operador = ? AND fecha = ? AND hora = ? "
                        + "AND indicativo = ? AND UPPER(banda) = ? AND UPPER(modo) = ? LIMIT 1";
                String sqlIns = "INSERT INTO qsos (uuid, operador, fecha, hora, indicativo, banda, modo, "
                        + "freq_tx, freq_rx, rst_sent, rst_rcvd, nombre, qth, grid, actividad, subido, "
                        + "actividad_numeral, puntos, num_contactos, comentario) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

                try (PreparedStatement psDup = conn.prepareStatement(sqlDup);
                     PreparedStatement psIns = conn.prepareStatement(sqlIns)) {
                    for (QsoImportado q : lista) {
                        String operador;
                        if (opciones.comoActividad()) {
                            // Cada contacto conserva el operador que lo hizo. Si el fichero no lo indica,
                            // queda sin operador (no se asigna a quien importa).
                            operador = q.operador();
                            // Si el "operador" del fichero no es ninguno de los nuestros (p. ej. un apodo de QRZ.com)
                            // pero el indicativo de la estación sí lo es, el contacto es de ese operador.
                            List<String> conocidos = obtenerListaOperadores();
                            if (!conocidos.contains(operador) && conocidos.contains(q.estacion())) {
                                operador = q.estacion();
                            }
                            if (operador.isEmpty()) sinOperador++;
                        } else {
                            // En el log principal solo entran los contactos de ese operador
                            // (o los que no indican operador en el fichero).
                            // También son suyos si el indicativo de la estación es el del operador elegido
                            // (QRZ.com, por ejemplo, pone en "operador" el apodo del usuario).
                            boolean esSuyo = q.operador().isEmpty()
                                    || q.operador().equalsIgnoreCase(opciones.operador())
                                    || q.estacion().equalsIgnoreCase(opciones.operador());
                            if (!esSuyo) {
                                otroOperador++;
                                continue;
                            }
                            operador = opciones.operador();
                        }

                        // En el log principal no se duplican contactos que ya existen
                        if (!opciones.comoActividad()) {
                            psDup.setString(1, operador);
                            psDup.setString(2, q.fecha());
                            psDup.setString(3, q.hora());
                            psDup.setString(4, q.indicativo());
                            psDup.setString(5, q.banda().toUpperCase());
                            psDup.setString(6, q.modo().toUpperCase());
                            try (ResultSet rsDup = psDup.executeQuery()) {
                                if (rsDup.next()) {
                                    omitidos++;
                                    continue;
                                }
                            }
                        }

                        psIns.setString(1, java.util.UUID.randomUUID().toString());
                        psIns.setString(2, operador);
                        psIns.setString(3, q.fecha());
                        psIns.setString(4, q.hora());
                        psIns.setString(5, q.indicativo());
                        psIns.setString(6, q.banda());
                        psIns.setString(7, q.modo());
                        psIns.setString(8, q.freq());
                        psIns.setString(9, q.freq());
                        psIns.setString(10, q.rstSent());
                        psIns.setString(11, q.rstRcvd());
                        psIns.setString(12, q.nombre());
                        psIns.setString(13, q.qth());
                        psIns.setString(14, q.grid());
                        psIns.setString(15, opciones.comoActividad() ? nombreInterno : "");
                        psIns.setInt(16, 0);
                        psIns.setInt(17, opciones.comoActividad() ? nuevoNumeral : 0);
                        psIns.setInt(18, 0);
                        psIns.setInt(19, 1);
                        psIns.setString(20, q.comentario());
                        psIns.executeUpdate();
                        importados++;
                    }
                }
                conn.commit();
            } catch (Exception exTx) {
                conn.rollback();
                throw exTx;
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Error al importar: " + ex.getMessage() + "\n\nNo se ha importado ningún contacto.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 4) Mostrar el resultado
        if (opciones.comoActividad()) {
            actualizarDesplegableActividades();
            seleccionarActividadEnDesplegable(nuevoNumeral);
            String mensajeAct = "Se ha creado la actividad '" + nombreVisible + "' con " + importados + " contactos.";
            if (!bandaActividad.isEmpty() || !modoActividad.isEmpty()) {
                mensajeAct += "\nBanda: " + (bandaActividad.isEmpty() ? "(libre)" : bandaActividad)
                        + " | Modo: " + (modoActividad.isEmpty() ? "(libre)" : modoActividad);
            } else {
                mensajeAct += "\nEl fichero mezcla bandas o modos, así que la actividad no tiene banda ni modo fijos.";
            }
            if (sinOperador > 0) {
                mensajeAct += "\n" + sinOperador + " contactos no indicaban operador y se han guardado sin operador.";
            }
            JOptionPane.showMessageDialog(this, mensajeAct,
                    "Importación finalizada", JOptionPane.INFORMATION_MESSAGE);
        } else {
            String mensaje = "Se han importado " + importados + " contactos al log principal de "
                    + opciones.operador() + ".";
            if (omitidos > 0) {
                mensaje += "\n" + omitidos + " contactos ya existían y se han omitido.";
            }
            if (otroOperador > 0) {
                mensaje += "\n" + otroOperador + " contactos son de otros operadores y no se han añadido a este log.";
            }
            if (!opciones.operador().equalsIgnoreCase(operadorActual)) {
                mensaje += "\n\nPara verlos, cambia al operador " + opciones.operador() + ".";
            }
            JOptionPane.showMessageDialog(this, mensaje, "Importación finalizada", JOptionPane.INFORMATION_MESSAGE);
        }
        cargarQSOsDesdeBaseDatos();
    }

    private String extraerCampoAdi(String registro, String etiqueta) {
        Pattern pattern = Pattern.compile("(?i)<" + etiqueta + ":(\\d+)>([^<]+)");
        Matcher matcher = pattern.matcher(registro);
        if (matcher.find()) {
            return matcher.group(2).trim();
        }
        return null;
    }

    /** Una opción en forma de tarjeta: el botón redondo con su explicación debajo; la elegida, con borde ámbar. */
    private static JPanel tarjetaOpcion(JRadioButton rb, String explicacion) {
        JPanel t = new JPanel(new BorderLayout(0, 2)) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean sel = rb.isSelected();
                g.setColor(sel ? new Color(52, 44, 22) : new Color(30, 40, 58));
                g.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 10, 10);
                g.setColor(sel ? TEMA_AMBAR : new Color(70, 84, 108));
                g.setStroke(new BasicStroke(sel ? 2f : 1f));
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 10, 10);
                g.dispose();
            }
        };
        t.setOpaque(false);
        t.setBorder(BorderFactory.createEmptyBorder(7, 10, 8, 12));
        rb.setOpaque(false);
        rb.setFont(rb.getFont().deriveFont(Font.BOLD, 13f));
        JLabel ex = new JLabel("<html><font color='#a9b5c7'>" + explicacion + "</font></html>");
        ex.setFont(ex.getFont().deriveFont(Font.PLAIN, 11.5f));
        ex.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 0));
        t.add(rb, BorderLayout.NORTH);
        t.add(ex, BorderLayout.CENTER);
        t.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter elegir = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { rb.setSelected(true); }
        };
        t.addMouseListener(elegir);
        ex.addMouseListener(elegir);
        rb.addItemListener(e -> t.repaint());
        return t;
    }

    private void abrirDialogoGestionActividades() {
        JDialog dialogo = new JDialog(this, "Nueva actividad", true);
        dialogo.setLayout(new BorderLayout(10, 10));

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.weightx = 1;

        JTextField txtNombreActividad = new JTextField(24);
        aplicarFiltroMayusculas(txtNombreActividad);

        // ¿Cómo se cuenta? Dos tarjetas: la elegida se ve con el borde ámbar
        JRadioButton rbSimple = new JRadioButton("Un contacto por estación", true);
        JRadioButton rbPuntos = new JRadioButton("Por puntos");
        ButtonGroup grupoTipo = new ButtonGroup();
        grupoTipo.add(rbSimple);
        grupoTipo.add(rbPuntos);
        JPanel tarjetaSimple = tarjetaOpcion(rbSimple, "Cada indicativo cuenta una vez (lo normal en activaciones y diplomas).");
        JPanel tarjetaPuntos = tarjetaOpcion(rbPuntos, "Cada banda, modo o indicativo especial da puntos (concursos).");

        // Contacto por estación: es en una sola banda y un modo, así que se preguntan
        JComboBox<String> cbBandaSimple = new JComboBox<>(BANDAS);
        JComboBox<String> cbModoSimple = new JComboBox<>(MODOS);
        // Se proponen la banda y el modo que hay puestos ahora
        if (cbBanda != null && cbBanda.getSelectedItem() != null) cbBandaSimple.setSelectedItem(cbBanda.getSelectedItem());
        if (cbModo != null && cbModo.getSelectedItem() != null) cbModoSimple.setSelectedItem(cbModo.getSelectedItem());
        JPanel filaBandaModo = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filaBandaModo.setOpaque(false);
        filaBandaModo.add(new JLabel("Banda:"));
        filaBandaModo.add(cbBandaSimple);
        filaBandaModo.add(new JLabel("   Modo:"));
        filaBandaModo.add(cbModoSimple);
        JButton btnCrear = new JButton("Crear actividad");
        btnCrear.setFont(btnCrear.getFont().deriveFont(Font.BOLD, 14f));
        btnCrear.setBackground(TEMA_AMBAR);
        btnCrear.setForeground(new Color(27, 20, 4));

        // RADIOCLUB: casilla para crear la actividad como del radioclub (solo si está configurado)
        boolean hayRadioclub = radioclub.radioclubConfigurado();
        JCheckBox chkActividadRC = new JCheckBox("Actividad del radioclub (" + radioclub.nombreRadioclub() + ")");
        chkActividadRC.setSelected(false);
        chkActividadRC.setVisible(hayRadioclub);
        // Al marcarla aparece el indicativo del radioclub con el que se harán los contactos
        JLabel lblIndRCAct = new JLabel("Indicativo del radioclub:");
        JComboBox<String> cbIndRCAct = new JComboBox<>(radioclub.opcionesIndicativoRadioclub().toArray(new String[0]));
        String elegidoAntes = ConfigApp.get("RADIOCLUB_IND_ELEGIDO", "");
        for (int i = 0; i < cbIndRCAct.getItemCount(); i++) if (cbIndRCAct.getItemAt(i).startsWith(elegidoAntes + ":")) cbIndRCAct.setSelectedIndex(i);
        JPanel filaIndRCAct = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filaIndRCAct.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 0));
        filaIndRCAct.add(lblIndRCAct);
        filaIndRCAct.add(cbIndRCAct);
        filaIndRCAct.setVisible(false);

        // En las de puntos no hace falta banda ni modo (salen de la tabla de puntos)
        Runnable verBandaModo = () -> {
            filaBandaModo.setVisible(rbSimple.isSelected());
            filaIndRCAct.setVisible(chkActividadRC.isSelected());
            tarjetaSimple.repaint();
            tarjetaPuntos.repaint();
            dialogo.pack();
        };
        rbSimple.addItemListener(ev -> verBandaModo.run());
        rbPuntos.addItemListener(ev -> verBandaModo.run());
        chkActividadRC.addActionListener(ev -> verBandaModo.run());

        int y = 0;
        JPanel filaNombre = new JPanel(new BorderLayout(14, 0));
        filaNombre.setOpaque(false);
        JLabel lblNombre = new JLabel("Nombre:");
        lblNombre.setFont(lblNombre.getFont().deriveFont(Font.BOLD, 13f));
        filaNombre.add(lblNombre, BorderLayout.WEST);
        filaNombre.add(txtNombreActividad, BorderLayout.CENTER);
        gbc.gridy = y++;
        panel.add(filaNombre, gbc);
        JLabel lblComo = new JLabel("¿Cómo se cuenta?");
        lblComo.setFont(lblComo.getFont().deriveFont(Font.BOLD, 13f));
        gbc.gridy = y++;
        gbc.insets = new Insets(12, 4, 4, 4);
        panel.add(lblComo, gbc);
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.gridy = y++; panel.add(tarjetaSimple, gbc);
        gbc.gridy = y++; panel.add(tarjetaPuntos, gbc);
        gbc.insets = new Insets(8, 4, 2, 4);
        gbc.gridy = y++; panel.add(filaBandaModo, gbc);
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.gridy = y++; panel.add(chkActividadRC, gbc);
        gbc.gridy = y++; panel.add(filaIndRCAct, gbc);
        JButton btnCancelarAct = new JButton("Cancelar");
        btnCancelarAct.setFont(btnCancelarAct.getFont().deriveFont(14f));
        btnCancelarAct.addActionListener(ev -> dialogo.dispose());
        JPanel filaBotonesAct = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        filaBotonesAct.setOpaque(false);
        filaBotonesAct.add(btnCrear);
        filaBotonesAct.add(btnCancelarAct);
        gbc.gridy = y;
        gbc.insets = new Insets(16, 4, 4, 4);
        panel.add(filaBotonesAct, gbc);

        btnCrear.addActionListener(e -> {
            String nombreVisible = txtNombreActividad.getText().trim().toUpperCase();
            if (nombreVisible.isEmpty()) {
                JOptionPane.showMessageDialog(dialogo, "Introduce un nombre para la actividad.", "Aviso", JOptionPane.WARNING_MESSAGE);
                return;
            }

            boolean esPorPuntos = rbPuntos.isSelected();
            String tipo = esPorPuntos ? "PUNTOS" : "SIMPLE";

            String bandaSimple = "";
            String modoSimple = "";
            if (!esPorPuntos) {
                Object b = cbBandaSimple.getSelectedItem();
                Object m = cbModoSimple.getSelectedItem();
                bandaSimple = (b == null) ? "" : b.toString().trim().toUpperCase();
                modoSimple = (m == null) ? "" : m.toString().trim().toUpperCase();

                if (bandaSimple.isEmpty() || modoSimple.isEmpty()) {
                    JOptionPane.showMessageDialog(dialogo,
                            "En una actividad de un contacto por estación debes elegir banda y modo.",
                            "Aviso", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }

            int nuevoNumeral;
            String nombreInterno;

            try (Connection conn = DatabaseManager.conectar()) {
                nuevoNumeral = 1;
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(numeral), 0) FROM actividades")) {
                    if (rs.next()) nuevoNumeral = rs.getInt(1) + 1;
                }
                soltarContactosHuerfanos(conn, nuevoNumeral);

                nombreInterno = nombreVisible + " #" + nuevoNumeral;
                String fechaHoy = LocalDate.now(ZoneOffset.UTC).toString();

                String sql = "INSERT INTO actividades (nombre, tipo, numeral, fecha_creacion, banda_simple, modo_simple, radioclub, indicativo_rc) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, nombreInterno);
                    pstmt.setString(2, tipo);
                    pstmt.setInt(3, nuevoNumeral);
                    pstmt.setString(4, fechaHoy);
                    pstmt.setString(5, bandaSimple);
                    pstmt.setString(6, modoSimple);
                    pstmt.setInt(7, chkActividadRC.isSelected() ? 1 : 0);
                    Object indRC = cbIndRCAct.getSelectedItem();
                    pstmt.setString(8, chkActividadRC.isSelected() && indRC != null ? indRC.toString() : "");
                    pstmt.executeUpdate();
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialogo,
                        "Error al crear actividad: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            dialogo.dispose();

            actualizarDesplegableActividades();
            seleccionarActividadEnDesplegable(nuevoNumeral);

            if (esPorPuntos) {
                abrirDialogoTablaPuntos(nombreInterno);
                aplicarRestriccionBandaModoActividad(nuevoNumeral);
                ponerBandaModoInicialActividad(true, nombreInterno, "", "");
            } else {
                // La aplicación (y la emisora, si el CAT está conectado) se pone en la banda y el modo de la actividad
                ponerBandaModoInicialActividad(false, nombreInterno, bandaSimple, modoSimple);
                JOptionPane.showMessageDialog(this,
                        "Actividad '" + nombreVisible + "' creada con éxito.\n" +
                                "Banda: " + bandaSimple + " | Modo: " + modoSimple,
                        "Éxito", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        dialogo.add(panel, BorderLayout.CENTER);
        verBandaModo.run();
        dialogo.setMinimumSize(new Dimension(520, 10));
        dialogo.pack();
        dialogo.setLocationRelativeTo(this);
        dialogo.setVisible(true);
    }

    /**
     * Al crear una actividad: se pone su banda y modo (y, con el CAT conectado, también en la emisora).
     * En las de puntos se empieza por la banda más alta (las actividades se hacen de día). Solo esta vez:
     * luego se puede cambiar de banda y modo cuando se quiera.
     */
    private void ponerBandaModoInicialActividad(boolean porPuntos, String nombreInterno, String bandaSimple, String modoSimple) {
        String banda, modo;
        if (!porPuntos) {
            banda = normalizarOpcion(bandaSimple, BANDAS);
            modo = normalizarOpcion(modoSimple, MODOS);
        } else {
            Map<String, Integer> bandas = cargarPuntosPorTipo(nombreInterno, "BANDA");
            Map<String, Integer> modos = cargarPuntosPorTipo(nombreInterno, "MODO");
            banda = null;
            double mejor = -1;
            for (String b : bandas.keySet()) {
                String bc = normalizarOpcion(b, BANDAS);
                InfoBanda ib = CONFIG_BANDAS.get(bc);
                if (ib == null) continue;
                double f = Double.parseDouble(ib.freq);
                if (f > mejor) { mejor = f; banda = bc; }
            }
            if (banda == null) return;
            String porDefecto = CONFIG_BANDAS.get(banda).modo;
            java.util.Set<String> permitidos = new HashSet<>();
            for (String m : modos.keySet()) permitidos.add(m.trim().toUpperCase());
            if (permitidos.isEmpty() || permitidos.contains(porDefecto)
                    || (permitidos.contains("SSB") && (porDefecto.equals("LSB") || porDefecto.equals("USB")))) {
                modo = porDefecto;
            } else {
                modo = null;
                for (String m : MODOS) if (permitidos.contains(m)) { modo = m; break; }
                if (modo == null) modo = porDefecto;
            }
        }
        if (banda == null || banda.isEmpty() || !CONFIG_BANDAS.containsKey(banda)) return;
        cbBanda.setSelectedItem(banda);
        String f = formatearHzDesdeMHz(CONFIG_BANDAS.get(banda).freq);
        cat.frecuenciaPorPrograma = true;
        try { txtFreqTx.setText(f); txtFreqRx.setText(f); } finally { cat.frecuenciaPorPrograma = false; }
        if (modo != null && !modo.isEmpty()) cbModo.setSelectedItem(modo);
        enviarFormularioAEmisora();
    }

    /** Con el CAT conectado: pone en la emisora la frecuencia y el modo que hay ahora en la pantalla. */
    private void enviarFormularioAEmisora() {
        if (catConectado) {
            long hz = cat.herciosDeTexto(txtFreqTx.getText());
            String modo = cbModo.getSelectedItem() == null ? "" : cbModo.getSelectedItem().toString();
            int codigo = cat.codigoModoOmniRig(modo, hz);
            if (hz > 0) {
                cat.ultimaCifraEscritaMs = System.currentTimeMillis();   // que la lectura del CAT no lo pise antes de cambiar
                cat.enviarOrden("actividad: " + modo, rig -> {
                    cat.ponerFrecuenciaEmisora(rig, (int) hz);
                    if (codigo != 0) Dispatch.put(rig, "Mode", new Variant(codigo));
                });
            }
        }
        cat.refrescarFrontal();
    }

    private void abrirDialogoGestionarActividades() {
        JDialog dialogo = new JDialog(this, "Mis actividades", true);
        dialogo.setSize(760, 440);
        dialogo.setLocationRelativeTo(this);
        dialogo.setLayout(new BorderLayout(10, 10));

        DefaultTableModel modelo = new DefaultTableModel(new Object[]{"Actividad", "Tipo", "Nº QSOs", "Radioclub",
                "Banda", "Modo", "Indicativo RC", "Numeral"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        JTable tabla = new JTable(modelo);
        tabla.setRowHeight(22);
        tabla.getTableHeader().setReorderingAllowed(false);
        tabla.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // Solo se ven tres columnas: Actividad, Cómo cuenta y Contactos (las demás sirven para saber qué actividad es)
        for (int col = 7; col >= 3; col--) tabla.getColumnModel().removeColumn(tabla.getColumnModel().getColumn(col));
        tabla.getColumnModel().getColumn(1).setHeaderValue("Cómo cuenta");
        tabla.getColumnModel().getColumn(2).setHeaderValue("Contactos");
        tabla.getColumnModel().getColumn(0).setPreferredWidth(240);
        tabla.getColumnModel().getColumn(1).setPreferredWidth(420);
        tabla.getColumnModel().getColumn(2).setPreferredWidth(80);
        // «Cómo cuenta» con las mismas palabras que en Nueva actividad (y la banda/modo o el radioclub si los tiene)
        tabla.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int f, int c) {
                int mf = t.convertRowIndexToModel(f);
                boolean puntos = "PUNTOS".equalsIgnoreCase(String.valueOf(v));
                String texto = puntos ? "Por puntos" : "Un contacto por estación";
                String banda = String.valueOf(modelo.getValueAt(mf, 4)), modo = String.valueOf(modelo.getValueAt(mf, 5));
                if (!puntos && !(banda + modo).isBlank()) texto += " · solo " + (banda + " " + modo).trim();
                if ("SÍ".equals(modelo.getValueAt(mf, 3))) texto += " · radioclub" + (String.valueOf(modelo.getValueAt(mf, 6)).isBlank() ? "" : " (" + modelo.getValueAt(mf, 6) + ")");
                return super.getTableCellRendererComponent(t, texto, sel, foco, f, c);
            }
        });
        tabla.getColumnModel().getColumn(2).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            { setHorizontalAlignment(CENTER); }
        });

        JScrollPane scroll = new JScrollPane(tabla);

        cargarActividadesEnTabla(modelo);

        JButton btnEliminar = new JButton("Eliminar actividad seleccionada");
        btnEliminar.setFocusable(false);
        btnEliminar.setBackground(new Color(200, 0, 0));
        btnEliminar.setForeground(Color.WHITE);

        btnEliminar.addActionListener(e -> {
            int fila = tabla.getSelectedRow();
            if (fila < 0) {
                JOptionPane.showMessageDialog(dialogo,
                        "Selecciona primero una actividad en la tabla.",
                        "Aviso", JOptionPane.WARNING_MESSAGE);
                return;
            }

            String nombreVisible = (String) modelo.getValueAt(fila, 0);
            int numQSOs = (int) modelo.getValueAt(fila, 2);

            // Actividad del radioclub: sin permiso no se borra (el ordenador del radioclub la conserva); solo se quita de este PC
            String[] rcAct = null;
            try (Connection c0 = DatabaseManager.conectar();
                 PreparedStatement ps0 = c0.prepareStatement("SELECT COALESCE(radioclub,0), COALESCE(uuid,'') FROM actividades WHERE nombre = ? OR nombre LIKE ? ORDER BY numeral DESC LIMIT 1")) {
                ps0.setString(1, nombreVisible);
                ps0.setString(2, nombreVisible + " #%");
                try (ResultSet rs0 = ps0.executeQuery()) { if (rs0.next() && rs0.getInt(1) == 1 && !rs0.getString(2).isEmpty()) rcAct = new String[]{rs0.getString(2)}; }
            } catch (Exception ignored) { }
            if (rcAct != null && !radioclub.soyOrdenadorRadioclub()) {
                if (!radioclub.tengoPermiso("PERMISOS")) {   // solo el PC Radioclub y los Super Admin pueden borrarlas
                    JOptionPane.showMessageDialog(dialogo, "'" + nombreVisible + "' es una actividad del radioclub y no se puede borrar.\n\n"
                                    + "Si no quieres verla, desmarca su casilla en la ventana que se abre ahora\n"
                                    + "y dejará de aparecer en tu ordenador.",
                            "Actividad del radioclub", JOptionPane.INFORMATION_MESSAGE);
                    radioclub.elegirActividadesRadioclub();
                    modelo.setRowCount(0);
                    cargarActividadesEnTabla(modelo);
                    actualizarDesplegableActividades();
                    return;
                }
                if (JOptionPane.showConfirmDialog(dialogo, "'" + nombreVisible + "' es una actividad del radioclub.\n\n"
                                + "Si la borras, se borrará también en el ordenador del radioclub y en los de todos los socios.\n"
                                + "Si solo quieres dejar de verla aquí, pulsa NO y desmárcala en Archivo → Sincronizar Datos del Radioclub.\n\n"
                                + "¿Borrarla para todos?", "Actividad del radioclub", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            }

            int confirmacion = JOptionPane.showConfirmDialog(dialogo,
                    "¿Borrar la actividad '" + nombreVisible + "'?\n\n" +
                            "Los contactos no se borran: se quedan en tu LOG.",
                    "Borrar actividad",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

            if (confirmacion != JOptionPane.YES_OPTION) return;

            try (Connection conn = DatabaseManager.conectar()) {
                String nombreInterno = null;
                int numeralBorrado = 0;
                String sqlBusca = "SELECT nombre, numeral FROM actividades WHERE " +
                        "nombre = ? OR nombre LIKE ? ORDER BY numeral DESC LIMIT 1";
                try (PreparedStatement ps = conn.prepareStatement(sqlBusca)) {
                    ps.setString(1, nombreVisible);
                    ps.setString(2, nombreVisible + " #%");
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            nombreInterno = rs.getString("nombre");
                            numeralBorrado = rs.getInt("numeral");
                        }
                    }
                }

                if (nombreInterno == null || nombreInterno.isEmpty()) {
                    JOptionPane.showMessageDialog(dialogo,
                            "No se encontró la actividad '" + nombreVisible + "' en la base de datos.",
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                try (PreparedStatement pstmt = conn.prepareStatement(
                        "DELETE FROM actividad_puntos WHERE actividad = ?")) {
                    pstmt.setString(1, nombreInterno);
                    pstmt.executeUpdate();
                }

                try (PreparedStatement pstmt = conn.prepareStatement(
                        "DELETE FROM actividades WHERE nombre = ?")) {
                    pstmt.setString(1, nombreInterno);
                    pstmt.executeUpdate();
                }
                // Los contactos siguen en el LOG Principal (con el nombre de la actividad en su columna),
                // pero ya no cuelgan de su número: así una actividad nueva no los "hereda"
                if (numeralBorrado > 0) soltarContactosHuerfanos(conn, numeralBorrado);

                JOptionPane.showMessageDialog(dialogo,
                        "Actividad '" + nombreVisible + "' borrada.\n" +
                                "Sus " + numQSOs + " contactos siguen en tu LOG.",
                        "Borrar actividad", JOptionPane.INFORMATION_MESSAGE);

                modelo.setRowCount(0);
                cargarActividadesEnTabla(modelo);
                actualizarDesplegableActividades();
                cargarQSOsDesdeBaseDatos();

            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialogo,
                        "Error al eliminar la actividad: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        JButton btnModificar = new JButton("Editar…");
        btnModificar.addActionListener(e -> {
            int fila = tabla.getSelectedRow();
            if (fila < 0) {
                JOptionPane.showMessageDialog(dialogo, "Selecciona primero una actividad en la tabla.", "Aviso", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int numeral = (int) modelo.getValueAt(tabla.convertRowIndexToModel(fila), 7);
            if (modificarActividad(dialogo, numeral)) {
                modelo.setRowCount(0);
                cargarActividadesEnTabla(modelo);
            }
        });
        JButton btnPuntos = new JButton("Tabla de puntos…");
        btnPuntos.setToolTipText("Bandas, modos y estaciones especiales que puntúan en una actividad por puntos");
        btnPuntos.addActionListener(e -> {
            int fila = tabla.getSelectedRow();
            if (fila < 0) {
                JOptionPane.showMessageDialog(dialogo, "Selecciona primero una actividad en la tabla.", "Aviso", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int mf = tabla.convertRowIndexToModel(fila);
            int numeral = (int) modelo.getValueAt(mf, 7);
            if (!"PUNTOS".equalsIgnoreCase(String.valueOf(modelo.getValueAt(mf, 1)))) {
                JOptionPane.showMessageDialog(dialogo, "Esta actividad cuenta un contacto por estación.\n"
                        + "Para usar puntos, cámbiala a 'Por puntos' con \"Editar…\".", "Tabla de puntos", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            abrirDialogoTablaPuntos(obtenerNombreInternoDeActividad(numeral));
            refrescarTrasCambiarActividad(numeral);
        });
        // Abrir: la deja elegida arriba para trabajar con ella
        java.util.function.IntSupplier numeralElegido = () -> {
            int fila = tabla.getSelectedRow();
            if (fila < 0) {
                JOptionPane.showMessageDialog(dialogo, "Selecciona primero una actividad en la tabla.", "Aviso", JOptionPane.WARNING_MESSAGE);
                return 0;
            }
            return (int) modelo.getValueAt(tabla.convertRowIndexToModel(fila), 7);
        };
        JButton btnAbrir = new JButton("Abrir");
        btnAbrir.setFont(btnAbrir.getFont().deriveFont(Font.BOLD));
        btnAbrir.addActionListener(e -> {
            int numeral = numeralElegido.getAsInt();
            if (numeral <= 0) return;
            dialogo.dispose();
            seleccionarActividadEnDesplegable(numeral);
        });
        JButton btnDiploma = new JButton("Diploma…");
        btnDiploma.addActionListener(e -> {
            int numeral = numeralElegido.getAsInt();
            if (numeral <= 0) return;
            dialogo.dispose();
            seleccionarActividadEnDesplegable(numeral);
            generarDiploma();
        });
        // Borrar: con el botón derecho (para no pulsarlo sin querer)
        JPopupMenu menuFila = new JPopupMenu();
        JMenuItem itemBorrar = new JMenuItem("Borrar actividad…");
        itemBorrar.addActionListener(e -> btnEliminar.doClick());
        menuFila.add(itemBorrar);
        tabla.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) btnAbrir.doClick(); }
            @Override public void mousePressed(MouseEvent e) { menuDerecho(e); }
            @Override public void mouseReleased(MouseEvent e) { menuDerecho(e); }
            private void menuDerecho(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int f = tabla.rowAtPoint(e.getPoint());
                if (f < 0) return;
                tabla.setRowSelectionInterval(f, f);
                menuFila.show(tabla, e.getX(), e.getY());
            }
        });

        // Como en la propuesta: la tabla, debajo los botones de la actividad, una línea de ayuda y «Cerrar» en el centro
        JPanel filaAcciones = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filaAcciones.add(btnAbrir);
        filaAcciones.add(btnModificar);
        filaAcciones.add(btnDiploma);
        filaAcciones.add(btnPuntos);
        // Borrar: en rojo y separado de los demás (pide confirmación; los contactos no se borran)
        JButton btnBorrarAct = new JButton("Borrar actividad…");
        btnBorrarAct.setBackground(new Color(90, 31, 26));
        btnBorrarAct.setForeground(new Color(255, 217, 212));
        btnBorrarAct.addActionListener(e -> btnEliminar.doClick());
        filaAcciones.add(Box.createHorizontalStrut(24));
        filaAcciones.add(btnBorrarAct);
        JButton btnCerrar = new JButton("Cerrar");
        btnCerrar.addActionListener(e -> dialogo.dispose());
        // Se habilitan según la actividad elegida («Tabla de puntos…» solo sale en las de puntos)
        Runnable botonesSegunFila = () -> {
            int fila = tabla.getSelectedRow();
            boolean hay = fila >= 0;
            btnAbrir.setEnabled(hay);
            btnModificar.setEnabled(hay);
            btnDiploma.setEnabled(hay);
            btnBorrarAct.setEnabled(hay);
            btnPuntos.setVisible(hay && "PUNTOS".equalsIgnoreCase(String.valueOf(modelo.getValueAt(tabla.convertRowIndexToModel(fila), 1))));
        };
        tabla.getSelectionModel().addListSelectionListener(e -> botonesSegunFila.run());
        if (modelo.getRowCount() > 0) tabla.setRowSelectionInterval(0, 0);
        botonesSegunFila.run();

        JLabel lblInfo = new JLabel("<html><div style='width:520px'><font color='#8796ad'>Doble clic en una actividad para abrirla. "
                + "Al borrar una actividad, los contactos no se borran: se quedan en tu LOG.</font></div></html>");
        lblInfo.setFont(lblInfo.getFont().deriveFont(Font.PLAIN, 12f));
        JPanel filaCerrar = new JPanel(new FlowLayout(FlowLayout.CENTER));
        filaCerrar.add(btnCerrar);
        JPanel abajo = new JPanel();
        abajo.setLayout(new BoxLayout(abajo, BoxLayout.Y_AXIS));
        abajo.setBorder(BorderFactory.createEmptyBorder(8, 12, 6, 12));
        for (JComponent x : new JComponent[]{filaAcciones, lblInfo, filaCerrar}) { x.setAlignmentX(Component.LEFT_ALIGNMENT); abajo.add(x); }
        lblInfo.setBorder(BorderFactory.createEmptyBorder(10, 6, 6, 0));
        tabla.setShowVerticalLines(false);
        tabla.setRowHeight(26);
        // Tamaño: se ven entre 6 y 12 actividades sin tener que bajar (contando el borde y la cabecera)
        int filasVer = Math.max(6, Math.min(12, modelo.getRowCount()));
        scroll.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12), BorderFactory.createLineBorder(TEMA_LINEA)));
        Insets bordeSc = scroll.getBorder().getBorderInsets(scroll);
        scroll.setPreferredSize(new Dimension(760, bordeSc.top + bordeSc.bottom + tabla.getTableHeader().getPreferredSize().height
                + filasVer * tabla.getRowHeight() + 4));
        dialogo.setMinimumSize(new Dimension(720, 380));
        dialogo.setResizable(true);

        dialogo.add(scroll, BorderLayout.CENTER);
        dialogo.add(abajo, BorderLayout.SOUTH);
        dialogo.pack();
        dialogo.setLocationRelativeTo(this);
        dialogo.setVisible(true);
    }

    /**
     * Contactos que apuntan a un número de actividad que ya no existe (de una actividad borrada):
     * se sueltan de ese número para que una actividad nueva con el mismo número empiece vacía.
     * Los contactos NO se borran: siguen en el LOG Principal.
     */
    static void soltarContactosHuerfanos(Connection conn, int numeral) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE qsos SET actividad_numeral = 0 WHERE actividad_numeral = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM actividades a WHERE a.numeral = ?)")) {
            ps.setInt(1, numeral);
            ps.setInt(2, numeral);
            ps.executeUpdate();
        }
    }

    private void cargarActividadesEnTabla(DefaultTableModel modelo) {
        String sql = "SELECT a.nombre, a.numeral, " +
                "COALESCE(a.tipo, 'SIMPLE') AS tipo, " +
                "(SELECT COUNT(*) FROM qsos q WHERE q.actividad_numeral = a.numeral) AS num_qsos, " +
                "COALESCE(a.radioclub, 0) AS radioclub, COALESCE(a.banda_simple, '') AS banda, " +
                "COALESCE(a.modo_simple, '') AS modo, COALESCE(a.indicativo_rc, '') AS ind_rc, COALESCE(a.uuid, '') AS uuid " +
                "FROM actividades a ORDER BY a.numeral ASC";
        try (Connection conn = DatabaseManager.conectar();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String nombreInterno = rs.getString("nombre");
                String nombreVisible = nombreInterno;
                int idx = nombreInterno.lastIndexOf(" #");
                if (idx > 0) nombreVisible = nombreInterno.substring(0, idx);
                if (!radioclub.actividadVisible(rs.getInt("radioclub"), rs.getString("uuid"))) continue;   // no elegida para este PC

                boolean puntos = "PUNTOS".equalsIgnoreCase(rs.getString("tipo"));
                modelo.addRow(new Object[]{
                        nombreVisible,
                        rs.getString("tipo"),
                        rs.getInt("num_qsos"),
                        rs.getInt("radioclub") == 1 ? "SÍ" : "",
                        puntos ? "(puntos)" : normalizarOpcion(rs.getString("banda"), BANDAS),
                        puntos ? "(puntos)" : rs.getString("modo"),
                        rs.getInt("radioclub") == 1 ? rs.getString("ind_rc") : "",
                        rs.getInt("numeral")
                });
            }
        } catch (Exception ex) {
            System.err.println("Error al cargar actividades: " + ex.getMessage());
        }
    }

    /** Después de cambiar una actividad: se recargan el desplegable y la vista (y su banda/modo si es la elegida). */
    private void refrescarTrasCambiarActividad(int numeral) {
        int elegida = obtenerNumeralDeActividadDesdeSeleccion();
        Object sel = cbSelectorActividad.getSelectedItem();
        actualizarDesplegableActividades();
        if (elegida == numeral) {
            seleccionarActividadEnDesplegable(numeral);   // vuelve a aplicar banda, modo y radioclub
        } else {
            rehaciendoDesplegableActividades = true;
            try { if (sel != null) cbSelectorActividad.setSelectedItem(sel); } finally { rehaciendoDesplegableActividades = false; }
        }
        cargarQSOsDesdeBaseDatos();
    }

    /** Ventana para cambiar nombre, tipo, banda, modo y radioclub de una actividad. Devuelve true si se guardó. */
    private boolean modificarActividad(Component padre, int numeral) {
        String nombreInterno = obtenerNombreInternoDeActividad(numeral);
        if (nombreInterno == null || nombreInterno.isEmpty()) return false;
        String tipo = "SIMPLE", banda = "", modo = "", indRc = "";
        boolean rc = false;
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(tipo,'SIMPLE'), COALESCE(banda_simple,''), COALESCE(modo_simple,''), "
                     + "COALESCE(radioclub,0), COALESCE(indicativo_rc,'') FROM actividades WHERE numeral = ?")) {
            ps.setInt(1, numeral);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { tipo = rs.getString(1); banda = rs.getString(2); modo = rs.getString(3); rc = rs.getInt(4) == 1; indRc = rs.getString(5); }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(padre, "Error al leer la actividad: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        String nombreVisibleAntes = nombreVisibleDeActividad(nombreInterno);

        JTextField txtNombre = new JTextField(nombreVisibleAntes, 22);
        aplicarFiltroMayusculas(txtNombre);
        JComboBox<String> cbTipo = new JComboBox<>(new String[]{"Un contacto por estación", "Por puntos"});
        cbTipo.setSelectedIndex("PUNTOS".equalsIgnoreCase(tipo) ? 1 : 0);
        JComboBox<String> cbBandaA = new JComboBox<>(BANDAS);
        JComboBox<String> cbModoA = new JComboBox<>(MODOS);
        if (!banda.isEmpty()) cbBandaA.setSelectedItem(normalizarOpcion(banda, BANDAS));
        if (!modo.isEmpty()) cbModoA.setSelectedItem(normalizarOpcion(modo, MODOS));
        boolean hayRc = radioclub.radioclubConfigurado();
        JCheckBox chkRc = new JCheckBox("Actividad " + radioclub.nombreRadioclub(), rc);
        JComboBox<String> cbIndRc = new JComboBox<>(radioclub.opcionesIndicativoRadioclub().toArray(new String[0]));
        for (int i = 0; i < cbIndRc.getItemCount(); i++) if (cbIndRc.getItemAt(i).equals(indRc)) cbIndRc.setSelectedIndex(i);
        JLabel lblBanda = new JLabel("Banda:"), lblModo = new JLabel("Modo:"), lblIndRc = new JLabel("Indicativo del radioclub:");

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 5, 5, 5);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        Object[][] filas = {{new JLabel("Nombre:"), txtNombre}, {new JLabel("Cómo se cuenta:"), cbTipo}, {lblBanda, cbBandaA}, {lblModo, cbModoA},
                {new JLabel(""), chkRc}, {lblIndRc, cbIndRc}};
        for (int i = 0; i < filas.length; i++) {
            g.gridy = i; g.gridx = 0; g.weightx = 0;
            p.add((Component) filas[i][0], g);
            g.gridx = 1; g.weightx = 1;
            p.add((Component) filas[i][1], g);
        }
        Runnable visibles = () -> {
            boolean simple = cbTipo.getSelectedIndex() == 0;
            lblBanda.setVisible(simple); cbBandaA.setVisible(simple);
            lblModo.setVisible(simple); cbModoA.setVisible(simple);
            chkRc.setVisible(hayRc);
            lblIndRc.setVisible(hayRc && chkRc.isSelected()); cbIndRc.setVisible(hayRc && chkRc.isSelected());
            Window w = SwingUtilities.getWindowAncestor(p);
            if (w != null) w.pack();
        };
        cbTipo.addActionListener(e -> visibles.run());
        chkRc.addActionListener(e -> visibles.run());
        visibles.run();

        int r = JOptionPane.showConfirmDialog(padre, p, "Modificar actividad", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return false;

        String nombreNuevo = txtNombre.getText().trim().toUpperCase();
        if (nombreNuevo.isEmpty()) {
            JOptionPane.showMessageDialog(padre, "El nombre no puede quedar vacío.", "Aviso", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        boolean porPuntos = cbTipo.getSelectedIndex() == 1;
        String internoNuevo = nombreNuevo + " #" + numeral;
        try (Connection conn = DatabaseManager.conectar()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE actividades SET nombre = ?, tipo = ?, banda_simple = ?, modo_simple = ?, "
                    + "radioclub = ?, indicativo_rc = ? WHERE numeral = ?")) {
                ps.setString(1, internoNuevo);
                ps.setString(2, porPuntos ? "PUNTOS" : "SIMPLE");
                boolean conBandaModo = !porPuntos;
                ps.setString(3, conBandaModo ? String.valueOf(cbBandaA.getSelectedItem()).toUpperCase() : "");
                ps.setString(4, conBandaModo ? String.valueOf(cbModoA.getSelectedItem()).toUpperCase() : "");
                ps.setInt(5, hayRc && chkRc.isSelected() ? 1 : 0);
                ps.setString(6, hayRc && chkRc.isSelected() && cbIndRc.getSelectedItem() != null ? cbIndRc.getSelectedItem().toString() : "");
                ps.setInt(7, numeral);
                ps.executeUpdate();
            }
            if (!internoNuevo.equals(nombreInterno)) {
                // El nombre también va en los contactos y en la tabla de puntos
                try (PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET actividad = ? WHERE actividad_numeral = ? OR actividad = ?")) {
                    ps.setString(1, internoNuevo); ps.setInt(2, numeral); ps.setString(3, nombreInterno); ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE actividad_puntos SET actividad = ? WHERE actividad = ?")) {
                    ps.setString(1, internoNuevo); ps.setString(2, nombreInterno); ps.executeUpdate();
                }
            }
            conn.commit();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(padre, "Error al guardar la actividad: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        // De Contacto Único a Por Puntos: se configura la tabla de puntos ahora
        if (porPuntos && !"PUNTOS".equalsIgnoreCase(tipo)) abrirDialogoTablaPuntos(internoNuevo);
        // Actividad del radioclub: sus contactos también tienen que ir al radioclub (p. ej. los importados de un .adi)
        if (hayRc && chkRc.isSelected()) {
            String indSel = cbIndRc.getSelectedItem() != null ? cbIndRc.getSelectedItem().toString() : "";
            compartirContactosDeActividadConRadioclub(padre, numeral, internoNuevo, indSel);
        }
        refrescarTrasCambiarActividad(numeral);
        return true;
    }

    /**
     * Los contactos de una actividad del radioclub que todavía no van al radioclub (por ejemplo, los importados
     * de un .adi antes de marcarla como del radioclub) se pasan al radioclub para que lleguen a los demás.
     * Si el mismo contacto ya está en el LOG Radioclub sin actividad, se mete ese en la actividad y se quita
     * la copia, para que no salga dos veces.
     */
    private void compartirContactosDeActividadConRadioclub(Component padre, int numeral, String nombreInterno, String indicativoRc) {
        int pendientes = 0;
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM qsos WHERE actividad_numeral = ? AND COALESCE(radioclub,0) = 0")) {
            ps.setInt(1, numeral);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) pendientes = rs.getInt(1); }
        } catch (Exception ex) {
            return;
        }
        if (pendientes == 0) return;
        int r = JOptionPane.showConfirmDialog(padre, "Esta actividad tiene " + pendientes + " contactos que no se comparten con el radioclub\n"
                        + "(por ejemplo, porque se importaron de un fichero .adi).\n\n"
                        + "¿Quieres compartirlos ahora para que lleguen a los demás ordenadores?\n"
                        + "Si alguno ya está en el LOG Radioclub, no se duplica: se mete en la actividad el que ya había.",
                "Actividad del radioclub", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;

        boolean permisoOtros = radioclub.syncActivo() && radioclub.tengoPermiso("CONTACTOS");
        String yo = operadorActual == null ? "" : operadorActual;
        int compartidos = 0, unidos = 0, sinPermiso = 0;
        try (Connection conn = DatabaseManager.conectar()) {
            conn.setAutoCommit(false);
            try {
                List<Object[]> filas = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement("SELECT id, COALESCE(operador,''), fecha, hora, indicativo, UPPER(COALESCE(banda,'')), "
                        + "UPPER(COALESCE(modo,'')), COALESCE(puntos,0), COALESCE(num_contactos,1) FROM qsos WHERE actividad_numeral = ? AND COALESCE(radioclub,0) = 0")) {
                    ps.setInt(1, numeral);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) filas.add(new Object[]{rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getInt(9)});
                    }
                }
                try (PreparedStatement psBus = conn.prepareStatement("SELECT id, COALESCE(operador,'') FROM qsos WHERE COALESCE(radioclub,0) = 1 "
                             + "AND COALESCE(actividad_numeral,0) = 0 AND fecha = ? AND hora = ? AND indicativo = ? AND UPPER(COALESCE(banda,'')) = ? "
                             + "AND UPPER(COALESCE(modo,'')) = ? AND (? = '' OR COALESCE(operador,'') = ?) LIMIT 1");
                     PreparedStatement psUnir = conn.prepareStatement("UPDATE qsos SET actividad_numeral = ?, actividad = ?, puntos = ?, num_contactos = ?, "
                             + "modificado_por = ?, act_uuid = COALESCE((SELECT uuid FROM actividades WHERE numeral = ? AND numeral > 0), '') WHERE id = ?");
                     PreparedStatement psBorrar = conn.prepareStatement("DELETE FROM qsos WHERE id = ?");
                     PreparedStatement psCompartir = conn.prepareStatement("UPDATE qsos SET radioclub = 1, "
                             + "estacion = CASE WHEN COALESCE(estacion,'') = '' THEN ? ELSE estacion END WHERE id = ?")) {
                    for (Object[] f : filas) {
                        String op = (String) f[1];
                        psBus.setString(1, (String) f[2]);
                        psBus.setString(2, (String) f[3]);
                        psBus.setString(3, (String) f[4]);
                        psBus.setString(4, (String) f[5]);
                        psBus.setString(5, (String) f[6]);
                        psBus.setString(6, op);
                        psBus.setString(7, op);
                        int idLog = -1;
                        String opLog = "";
                        try (ResultSet rs = psBus.executeQuery()) { if (rs.next()) { idLog = rs.getInt(1); opLog = rs.getString(2); } }
                        if (idLog > 0) {
                            // Ya está en el LOG Radioclub: se mete ese en la actividad (si se puede tocar) y se quita la copia
                            boolean puedo = opLog.isEmpty() || opLog.equalsIgnoreCase(yo) || permisoOtros || !radioclub.syncActivo();
                            if (!puedo) { sinPermiso++; continue; }
                            psUnir.setInt(1, numeral);
                            psUnir.setString(2, nombreInterno);
                            psUnir.setInt(3, (Integer) f[7]);
                            psUnir.setInt(4, (Integer) f[8]);
                            psUnir.setString(5, yo);
                            psUnir.setInt(6, numeral);
                            psUnir.setInt(7, idLog);
                            psUnir.executeUpdate();
                            psBorrar.setInt(1, (Integer) f[0]);
                            psBorrar.executeUpdate();
                            unidos++;
                        } else {
                            psCompartir.setString(1, indicativoRc == null ? "" : indicativoRc);
                            psCompartir.setInt(2, (Integer) f[0]);
                            psCompartir.executeUpdate();
                            compartidos++;
                        }
                    }
                }
                conn.commit();
            } catch (Exception exTx) {
                conn.rollback();
                throw exTx;
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(padre, "No se han podido compartir los contactos: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String msg = "Contactos compartidos con el radioclub: " + (compartidos + unidos) + ".";
        if (unidos > 0) msg += "\n" + unidos + " ya estaban en el LOG Radioclub: se han metido en la actividad sin duplicarlos.";
        if (sinPermiso > 0) msg += "\n\n" + sinPermiso + " son de otros operadores y tu papel no tiene permiso para cambiarlos.\n"
                + "Hazlo desde el PC Radioclub (Modificar actividad → Aceptar).";
        msg += "\n\nPulsa Sincronizar para enviarlos a los demás.";
        JOptionPane.showMessageDialog(padre, msg, "Actividad del radioclub", JOptionPane.INFORMATION_MESSAGE);
    }

    private boolean esBandaConfigurada(int numeralActividad, String banda) {
        if (banda == null || banda.trim().isEmpty() || numeralActividad <= 0) return false;
        String nombreInterno = obtenerNombreInternoDeActividad(numeralActividad);
        if (nombreInterno.isEmpty()) return false;
        String sql = "SELECT 1 FROM actividad_puntos WHERE actividad = ? AND tipo = 'BANDA' AND UPPER(valor) = UPPER(?) LIMIT 1";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, nombreInterno);
            pstmt.setString(2, banda.trim());
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void abrirDialogoTablaPuntos(String actividad) {
        JDialog dialogo = new JDialog(this, "Tabla de Puntos - " + actividad, true);
        dialogo.setSize(680, 640);
        dialogo.setLocationRelativeTo(this);
        dialogo.setLayout(new BorderLayout(10, 10));

        JLabel lblInfo = new JLabel("<html>Configura los puntos de la actividad:<br>" +
                "<b>Bandas</b> y <b>Modos</b>: marca los que puntúan y asigna puntos (0-10).<br>" +
                "<b>Estaciones Especiales</b>: añade indicativos a mano y asigna puntos (0-10).</html>");
        lblInfo.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));

        JTabbedPane pestanas = new JTabbedPane();

        Map<String, Integer> puntosBandas = cargarPuntosPorTipo(actividad, "BANDA");
        Map<String, Integer> puntosModos = cargarPuntosPorTipo(actividad, "MODO");
        Map<String, Integer> puntosEspeciales = cargarPuntosPorTipo(actividad, "ESPECIAL");

        Map<String, JCheckBox> checksBandas = new LinkedHashMap<>();
        Map<String, JComboBox<Integer>> combosBandas = new LinkedHashMap<>();
        JPanel panelBandas = crearPanelPuntosConChecks(
                BANDAS_PUNTOS, puntosBandas, checksBandas, combosBandas);
        pestanas.addTab("Bandas", new JScrollPane(panelBandas));

        Map<String, JCheckBox> checksModos = new LinkedHashMap<>();
        Map<String, JComboBox<Integer>> combosModos = new LinkedHashMap<>();
        JPanel panelModos = crearPanelPuntosConChecks(
                MODOS_PUNTOS, puntosModos, checksModos, combosModos);
        pestanas.addTab("Modos", new JScrollPane(panelModos));

        List<FilaEspecial> filasEspeciales = new ArrayList<>();

        for (Map.Entry<String, Integer> e : puntosEspeciales.entrySet()) {
            filasEspeciales.add(new FilaEspecial(e.getKey(), e.getValue()));
        }

        // RADIOCLUB: en una actividad del radioclub se añaden sus indicativos con 0 puntos
        // (si no estaban ya); el operador les pondrá la puntuación que quiera.
        if (radioclub.esActividadDelRadioclub(obtenerNumeralDeActividadPorNombre(actividad))) {
            for (String opcion : radioclub.opcionesIndicativoRadioclub()) {
                String ind = opcion.substring(opcion.indexOf(": ") + 2).trim().toUpperCase();
                boolean yaEsta = false;
                for (FilaEspecial f : filasEspeciales) {
                    if (f.indicativo.equalsIgnoreCase(ind)) { yaEsta = true; break; }
                }
                if (!yaEsta) filasEspeciales.add(new FilaEspecial(ind, 0));
            }
        }

        JPanel panelListaEspeciales = new JPanel();
        panelListaEspeciales.setLayout(new BoxLayout(panelListaEspeciales, BoxLayout.Y_AXIS));
        panelListaEspeciales.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JScrollPane scrollEspeciales = new JScrollPane(panelListaEspeciales);
        scrollEspeciales.getVerticalScrollBar().setUnitIncrement(16);

        final Runnable[] refrescarRef = new Runnable[1];
        refrescarRef[0] = () -> {
            panelListaEspeciales.removeAll();

            for (FilaEspecial fila : filasEspeciales) {
                JPanel panelFila = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));

                JButton btnEliminarFila = new JButton("−");
                btnEliminarFila.setForeground(Color.WHITE);
                btnEliminarFila.setBackground(new Color(200, 0, 0));
                btnEliminarFila.setFocusPainted(false);
                btnEliminarFila.setMargin(new Insets(2, 8, 2, 8));
                btnEliminarFila.setToolTipText("Eliminar esta estación especial");
                btnEliminarFila.addActionListener(ev -> {
                    filasEspeciales.remove(fila);
                    refrescarRef[0].run();
                });

                JButton btnEditarFila = new JButton("✎");
                btnEditarFila.setFocusPainted(false);
                btnEditarFila.setMargin(new Insets(2, 6, 2, 6));
                btnEditarFila.setToolTipText("Editar el indicativo");
                btnEditarFila.addActionListener(ev -> {
                    String nuevo = pedirIndicativoMayusculas(dialogo,
                            "Nuevo indicativo:", "Editar indicativo", fila.indicativo);
                    if (nuevo == null) return;
                    nuevo = nuevo.trim().toUpperCase();
                    if (nuevo.isEmpty()) return;
                    fila.indicativo = nuevo;
                    refrescarRef[0].run();
                });

                JLabel lblIndicativo = new JLabel(fila.indicativo);
                lblIndicativo.setPreferredSize(new Dimension(120, 24));

                JComboBox<Integer> comboL = new JComboBox<>();
                for (int i = 0; i <= 10; i++) comboL.addItem(i);
                comboL.setSelectedItem(fila.puntos);
                comboL.addActionListener(ev -> fila.puntos = (Integer) comboL.getSelectedItem());

                panelFila.add(btnEliminarFila);
                panelFila.add(btnEditarFila);
                panelFila.add(lblIndicativo);
                panelFila.add(new JLabel("Puntos:"));
                panelFila.add(comboL);

                panelListaEspeciales.add(panelFila);
            }

            panelListaEspeciales.revalidate();
            panelListaEspeciales.repaint();
        };
        refrescarRef[0].run();

        JTextField txtNuevaEspecial = new JTextField(12);
        aplicarFiltroMayusculas(txtNuevaEspecial);
        JButton btnAnadirEspecial = new JButton("Añadir estación especial");

        Runnable anadirEspecial = () -> {
            String ind = txtNuevaEspecial.getText().trim().toUpperCase();
            if (ind.isEmpty()) {
                JOptionPane.showMessageDialog(dialogo,
                        "Escribe primero el indicativo de la estación especial.",
                        "Aviso", JOptionPane.WARNING_MESSAGE);
                return;
            }
            for (FilaEspecial f : filasEspeciales) {
                if (f.indicativo.equalsIgnoreCase(ind)) {
                    JOptionPane.showMessageDialog(dialogo,
                            "La estación especial '" + ind + "' ya está en la lista.",
                            "Aviso", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }
            filasEspeciales.add(new FilaEspecial(ind, 0));
            txtNuevaEspecial.setText("");
            refrescarRef[0].run();
        };

        btnAnadirEspecial.addActionListener(ev -> anadirEspecial.run());
        txtNuevaEspecial.addActionListener(ev -> anadirEspecial.run());

        JPanel panelSuperiorEspeciales = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        panelSuperiorEspeciales.add(new JLabel("Indicativo:"));
        panelSuperiorEspeciales.add(txtNuevaEspecial);
        panelSuperiorEspeciales.add(btnAnadirEspecial);

        JPanel panelEspeciales = new JPanel(new BorderLayout());
        panelEspeciales.add(panelSuperiorEspeciales, BorderLayout.NORTH);
        panelEspeciales.add(scrollEspeciales, BorderLayout.CENTER);

        pestanas.addTab("Estaciones Especiales", panelEspeciales);

        JButton btnGuardar = new JButton("Guardar cambios");
        btnGuardar.setBackground(new Color(0, 130, 0));
        btnGuardar.setForeground(Color.WHITE);
        btnGuardar.setFocusPainted(false);

        btnGuardar.addActionListener(e -> {
            try (Connection conn = DatabaseManager.conectar()) {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM actividad_puntos WHERE actividad = ?")) {
                    del.setString(1, actividad);
                    del.executeUpdate();
                }

                String sqlIns = "INSERT INTO actividad_puntos (actividad, tipo, valor, puntos) VALUES (?, ?, ?, ?)";
                try (PreparedStatement ins = conn.prepareStatement(sqlIns)) {
                    insertarSeleccionados(ins, actividad, "BANDA", checksBandas, combosBandas);
                    insertarSeleccionados(ins, actividad, "MODO", checksModos, combosModos);
                    insertarFilasEspeciales(ins, actividad, filasEspeciales);
                }

                JOptionPane.showMessageDialog(dialogo,
                        "Tabla de puntos guardada correctamente.",
                        "Éxito", JOptionPane.INFORMATION_MESSAGE);

                aplicarRestriccionBandaModoActividad(obtenerNumeralDeActividadDesdeSeleccion());
                cargarQSOsDesdeBaseDatos();
                dialogo.dispose();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialogo,
                        "Error al guardar la tabla: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        JButton btnCerrar = new JButton("Cerrar");
        btnCerrar.addActionListener(e -> dialogo.dispose());

        JPanel panelBotones = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        panelBotones.add(btnCerrar);
        panelBotones.add(btnGuardar);

        dialogo.add(lblInfo, BorderLayout.NORTH);
        dialogo.add(pestanas, BorderLayout.CENTER);
        dialogo.add(panelBotones, BorderLayout.SOUTH);
        dialogo.setVisible(true);
    }

    private JPanel crearPanelPuntosConChecks(String[] valores,
                                             Map<String, Integer> puntosActuales,
                                             Map<String, JCheckBox> checksOut,
                                             Map<String, JComboBox<Integer>> combosOut) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 6, 3, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int fila = 0;
        for (String valor : valores) {
            String clave = valor.toUpperCase();
            boolean marcado = puntosActuales.containsKey(clave);
            int puntos = marcado ? puntosActuales.get(clave) : 0;

            JCheckBox chk = new JCheckBox(valor, marcado);
            JComboBox<Integer> combo = new JComboBox<>();
            for (int i = 0; i <= 10; i++) combo.addItem(i);
            combo.setSelectedItem(Math.max(0, Math.min(10, puntos)));
            combo.setEnabled(marcado);

            chk.addActionListener(e -> combo.setEnabled(chk.isSelected()));

            checksOut.put(valor, chk);
            combosOut.put(valor, combo);

            gbc.gridx = 0;
            gbc.gridy = fila;
            gbc.weightx = 0.0;
            panel.add(chk, gbc);
            gbc.gridx = 1;
            gbc.weightx = 1.0;
            panel.add(new JLabel("Puntos:"), gbc);
            gbc.gridx = 2;
            gbc.weightx = 0.0;
            panel.add(combo, gbc);

            fila++;
        }
        return panel;
    }

    private Map<String, Integer> cargarPuntosPorTipo(String actividad, String tipo) {
        Map<String, Integer> mapa = new LinkedHashMap<>();
        String sql = "SELECT valor, puntos FROM actividad_puntos WHERE actividad = ? AND tipo = ?";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, actividad);
            pstmt.setString(2, tipo);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    mapa.put(rs.getString("valor").toUpperCase(), rs.getInt("puntos"));
                }
            }
        } catch (Exception ignored) {
        }
        return mapa;
    }

    private void insertarSeleccionados(PreparedStatement ins, String actividad, String tipo,
                                       Map<String, JCheckBox> checks,
                                       Map<String, JComboBox<Integer>> combos) throws Exception {
        for (Map.Entry<String, JCheckBox> entry : checks.entrySet()) {
            String valor = entry.getKey();
            JCheckBox chk = entry.getValue();
            if (!chk.isSelected()) continue;
            JComboBox<Integer> combo = combos.get(valor);
            int puntos = combo == null ? 0 : (Integer) combo.getSelectedItem();

            ins.setString(1, actividad);
            ins.setString(2, tipo);
            ins.setString(3, valor.toUpperCase());
            ins.setInt(4, puntos);
            ins.addBatch();
        }
        ins.executeBatch();
    }

    private void insertarFilasEspeciales(PreparedStatement ins, String actividad, List<FilaEspecial> filas) throws Exception {
        for (FilaEspecial fila : filas) {
            if (fila.indicativo == null || fila.indicativo.trim().isEmpty()) continue;
            ins.setString(1, actividad);
            ins.setString(2, "ESPECIAL");
            ins.setString(3, fila.indicativo.toUpperCase());
            ins.setInt(4, fila.puntos);
            ins.addBatch();
        }
        ins.executeBatch();
    }

    /** Orden del tabulador: Indicativo, Nombre, QTH, RST S, RST R, Grid y después el resto de campos. */
    private void configurarOrdenTabulador() {
        setFocusTraversalPolicy(new LayoutFocusTraversalPolicy() {
            private Component nombreVisible() {
                return (cbNombres != null && cbNombres.isShowing()) ? cbNombres : txtNombre;
            }

            private List<Component> orden() {
                List<Component> lista = new ArrayList<>();
                lista.add(txtIndicativo);
                lista.add(nombreVisible());
                lista.add(txtQth);
                lista.add(txtRstSent);
                lista.add(txtRstRcvd);
                lista.add(txtGrid);
                lista.add(txtComentario);
                lista.add(txtFecha);
                lista.add(txtHora);
                lista.add(cbBanda);
                lista.add(cbModo);
                lista.add(txtFreqTx);
                lista.add(txtFreqRx);
                lista.removeIf(comp -> comp == null || !comp.isEnabled() || !comp.isShowing());
                return lista;
            }

            @Override
            public Component getComponentAfter(Container raiz, Component actual) {
                if (actual == txtNombre || actual == cbNombres) actual = nombreVisible();
                List<Component> lista = orden();
                int i = lista.indexOf(actual);
                if (i >= 0) return lista.get((i + 1) % lista.size());
                return super.getComponentAfter(raiz, actual);
            }

            @Override
            public Component getComponentBefore(Container raiz, Component actual) {
                if (actual == txtNombre || actual == cbNombres) actual = nombreVisible();
                List<Component> lista = orden();
                int i = lista.indexOf(actual);
                if (i >= 0) return lista.get((i - 1 + lista.size()) % lista.size());
                return super.getComponentBefore(raiz, actual);
            }
        });
    }

    /** En actividades por puntos, limita los desplegables de banda y modo a los configurados en su tabla de puntos. */
    private void aplicarRestriccionBandaModoActividad(int numeral) {
        bandasPuntosPermitidas = new ArrayList<>();
        modosPuntosPermitidos = new ArrayList<>();

        if (numeral > 0 && "PUNTOS".equals(obtenerTipoActividadPorNumeral(numeral))) {
            String nombreInterno = obtenerNombreInternoDeActividad(numeral);
            if (nombreInterno != null && !nombreInterno.isEmpty()) {
                bandasPuntosPermitidas = filtrarOpcionesConfiguradas(BANDAS, cargarPuntosPorTipo(nombreInterno, "BANDA"));
                modosPuntosPermitidos = filtrarOpcionesConfiguradas(MODOS, cargarPuntosPorTipo(nombreInterno, "MODO"));
            }
        }

        if (cbBanda == null || cbModo == null) return;

        // Primero los modos, para que al cambiar de banda pueda elegirse el modo por defecto de esa banda
        actualizarOpcionesCombo(cbModo,
                modosPuntosPermitidos.isEmpty() ? MODOS : modosPuntosPermitidos.toArray(new String[0]));
        actualizarOpcionesCombo(cbBanda,
                bandasPuntosPermitidas.isEmpty() ? BANDAS : bandasPuntosPermitidas.toArray(new String[0]));
    }

    private List<String> filtrarOpcionesConfiguradas(String[] todas, Map<String, Integer> configuradas) {
        List<String> resultado = new ArrayList<>();
        for (String opcion : todas) {
            if (!opcion.isEmpty() && configuradas.containsKey(opcion.toUpperCase())) {
                resultado.add(opcion);
            }
        }
        return resultado;
    }

    private void actualizarOpcionesCombo(JComboBox<String> combo, String[] opciones) {
        ComboBoxModel<String> actual = combo.getModel();
        boolean iguales = actual.getSize() == opciones.length;
        for (int i = 0; iguales && i < opciones.length; i++) {
            if (!opciones[i].equals(actual.getElementAt(i))) iguales = false;
        }
        if (iguales) return;

        Object seleccionado = combo.getSelectedItem();
        combo.setModel(new DefaultComboBoxModel<>(opciones));

        boolean sigueValido = false;
        if (seleccionado != null) {
            for (String opcion : opciones) {
                if (opcion.equals(seleccionado)) {
                    sigueValido = true;
                    break;
                }
            }
        }
        if (sigueValido) {
            combo.setSelectedItem(seleccionado);
        } else {
            // La selección anterior ya no existe: se elige la primera opción para que
            // se apliquen los valores por defecto de esa banda.
            combo.setSelectedIndex(-1);
            if (opciones.length > 0) combo.setSelectedIndex(0);
        }
    }

    private boolean opcionPermitida(List<String> permitidas, String valor, boolean esModo) {
        if (valor == null || valor.isEmpty()) return false;
        for (String p : permitidas) {
            if (p.equalsIgnoreCase(valor)) return true;
            if (esModo && "SSB".equalsIgnoreCase(p)
                    && ("USB".equalsIgnoreCase(valor) || "LSB".equalsIgnoreCase(valor))) {
                return true;
            }
        }
        return false;
    }

    /** Pide un indicativo en un cuadro de diálogo, forzando mayúsculas mientras se escribe. */
    private String pedirIndicativoMayusculas(Component padre, String mensaje, String titulo, String inicial) {
        JTextField campo = new JTextField(20);
        aplicarFiltroMayusculas(campo);
        campo.setText(inicial == null ? "" : inicial);
        Object[] contenido = {mensaje, campo};
        int resp = JOptionPane.showConfirmDialog(padre, contenido, titulo,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (resp != JOptionPane.OK_OPTION) return null;
        return campo.getText();
    }

    void aplicarFiltroMayusculas(JTextField campo) {
        if (campo == null) return;
        ((AbstractDocument) campo.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
                if (string != null) super.insertString(fb, offset, string.toUpperCase(), attr);
            }

            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                if (text != null) super.replace(fb, offset, length, text.toUpperCase(), attrs);
            }
        });
    }

    private void abrirDialogoEditarOperadores() {
        JDialog dialogo = new JDialog(this, "Configurar Operadores", true);
        dialogo.setSize(720, 380);
        dialogo.setLocationRelativeTo(this);
        dialogo.setLayout(new BorderLayout(10, 10));

        DefaultTableModel modelo = new DefaultTableModel(new Object[]{"Operador", "Indicativo HF", "Indicativo CB", "Nombre", "QTH", "Grid"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        JTable tabla = new JTable(modelo);
        tabla.setRowHeight(22);
        tabla.getTableHeader().setReorderingAllowed(false);
        JScrollPane scroll = new JScrollPane(tabla);
        scroll.setBorder(BorderFactory.createTitledBorder("Operadores guardados"));

        Runnable recargar = () -> {
            modelo.setRowCount(0);
            for (String op : obtenerListaOperadores()) {
                String[] d = leerDatosOperador(op);
                modelo.addRow(new Object[]{op, d[0], d[1], d[2], d[3], d[4]});
            }
        };
        recargar.run();

        JButton btnAnadir = new JButton("Añadir");
        JButton btnEditar = new JButton("Editar");
        JButton btnEliminar = new JButton("Eliminar");
        JButton btnCerrar = new JButton("Cerrar");

        btnAnadir.addActionListener(e -> {
            String[] datos = pedirDatosOperador(dialogo, "Añadir operador", null);
            if (datos == null) return;
            String nuevo = idOperador(datos);
            if (nuevo.isEmpty()) return;

            List<String> lista = obtenerListaOperadores();
            if (lista.contains(nuevo)) {
                JOptionPane.showMessageDialog(dialogo,
                        "El operador '" + nuevo + "' ya existe.", "Aviso", JOptionPane.WARNING_MESSAGE);
                return;
            }
            lista.add(nuevo);
            guardarListaOperadores(lista);
            guardarDatosOperador(nuevo, datos);
            operadorActual = nuevo;
            ConfigApp.put("OPERADOR_ACTUAL", operadorActual);
            actualizarBotonOperador();
            recargar.run();
            cargarQSOsDesdeBaseDatos();
        });

        btnEditar.addActionListener(e -> {
            int fila = tabla.getSelectedRow();
            if (fila < 0) {
                JOptionPane.showMessageDialog(dialogo,
                        "Selecciona un operador para editar.", "Aviso", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String antiguo = (String) modelo.getValueAt(fila, 0);
            if (!radioclub.comprobarClaveOperador(dialogo, antiguo, "editarlo")) return;
            String[] datos = pedirDatosOperador(dialogo, "Editar operador " + antiguo, leerDatosOperador(antiguo));
            if (datos == null) return;
            String nuevo = idOperador(datos);
            if (nuevo.isEmpty()) return;
            if (nuevo.equals(antiguo)) {
                // Solo cambian los datos de la ficha (CB, nombre, QTH, grid)
                guardarDatosOperador(nuevo, datos);
                recargar.run();
                return;
            }

            List<String> lista = obtenerListaOperadores();
            if (lista.contains(nuevo)) {
                JOptionPane.showMessageDialog(dialogo,
                        "El operador '" + nuevo + "' ya existe.", "Aviso", JOptionPane.WARNING_MESSAGE);
                return;
            }
            guardarDatosOperador(nuevo, datos);
            if (!radioclub.operadorTieneClave(nuevo)) {   // al cambiarle el indicativo conserva su contraseña
                ConfigApp.put("OPERADOR_FICHA_" + nuevo + "_SAL", ConfigApp.get("OPERADOR_FICHA_" + antiguo + "_SAL", ""));
                ConfigApp.put("OPERADOR_FICHA_" + nuevo + "_CLAVE", ConfigApp.get("OPERADOR_FICHA_" + antiguo + "_CLAVE", ""));
            }

            int idx = lista.indexOf(antiguo);
            if (idx >= 0) lista.set(idx, nuevo);
            guardarListaOperadores(lista);

            try (Connection conn = DatabaseManager.conectar();
                 PreparedStatement pstmt = conn.prepareStatement(
                         "UPDATE qsos SET operador = ? WHERE operador = ?")) {
                pstmt.setString(1, nuevo);
                pstmt.setString(2, antiguo);
                pstmt.executeUpdate();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialogo,
                        "Error al renombrar QSOs: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (operadorActual.equals(antiguo)) {
                operadorActual = nuevo;
                ConfigApp.put("OPERADOR_ACTUAL", operadorActual);
                actualizarBotonOperador();
            }
            recargar.run();
            cargarQSOsDesdeBaseDatos();
        });

        btnEliminar.addActionListener(e -> {
            int fila = tabla.getSelectedRow();
            if (fila < 0) {
                JOptionPane.showMessageDialog(dialogo,
                        "Selecciona un operador para eliminar.", "Aviso", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String op = (String) modelo.getValueAt(fila, 0);
            if (op.equals(operadorActual)) {
                JOptionPane.showMessageDialog(dialogo,
                        "No puedes eliminar el operador activo.\nCambia de operador primero.",
                        "Aviso", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!radioclub.comprobarClaveOperador(dialogo, op, "eliminarlo")) return;
            int resp = JOptionPane.showConfirmDialog(dialogo,
                    "¿Eliminar al operador '" + op + "' de la lista?\n" +
                            "Sus QSOs NO se borrarán, seguirán en la BD.",
                    "Confirmar", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (resp != JOptionPane.YES_OPTION) return;

            List<String> lista = obtenerListaOperadores();
            lista.remove(op);
            guardarListaOperadores(lista);
            recargar.run();
        });

        btnCerrar.addActionListener(e -> dialogo.dispose());

        JPanel panelBotones = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        panelBotones.add(btnAnadir);
        panelBotones.add(btnEditar);
        panelBotones.add(btnEliminar);
        panelBotones.add(btnCerrar);

        dialogo.add(scroll, BorderLayout.CENTER);
        dialogo.add(panelBotones, BorderLayout.SOUTH);
        dialogo.setVisible(true);
    }

    /** true mientras se rellena el desplegable de actividades (entonces no se cambia la banda ni la emisora). */
    boolean rehaciendoDesplegableActividades = false;

    void actualizarDesplegableActividades() {
        rehaciendoDesplegableActividades = true;
        try {
            rellenarDesplegableActividades();
        } finally {
            rehaciendoDesplegableActividades = false;
        }
    }

    private void rellenarDesplegableActividades() {
        cbSelectorActividad.removeAllItems();
        cbSelectorActividad.addItem("LOG Principal");
        if (radioclub.radioclubConfigurado()) cbSelectorActividad.addItem(NOMBRE_VISTA_RC);

        // Actividades propias y del radioclub, cada grupo con su título (los títulos no se pueden elegir)
        List<String> propias = new ArrayList<>(), delRadioclub = new ArrayList<>();
        String sql = "SELECT nombre, numeral, COALESCE(radioclub, 0) AS rc, COALESCE(uuid, '') AS uuid FROM actividades ORDER BY numeral ASC";
        try (Connection conn = DatabaseManager.conectar();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                if (!radioclub.actividadVisible(rs.getInt("rc"), rs.getString("uuid"))) continue;   // no elegida para este PC
                String nombreInterno = rs.getString("nombre");
                String nombreVisible = nombreInterno;
                int idx = nombreInterno.lastIndexOf(" #");
                if (idx > 0) nombreVisible = nombreInterno.substring(0, idx);

                int numeral = rs.getInt("numeral");
                (rs.getInt("rc") == 1 ? delRadioclub : propias).add(numeral + " - " + nombreVisible);
            }
        } catch (Exception ignored) {
        }
        if (!propias.isEmpty()) {
            cbSelectorActividad.addItem(TITULO_ACTIVIDADES);
            for (String a : propias) cbSelectorActividad.addItem(a);
        }
        if (!delRadioclub.isEmpty()) {
            cbSelectorActividad.addItem(TITULO_ACTIVIDADES_RC);
            for (String a : delRadioclub) cbSelectorActividad.addItem(a);
        }
        // BANDA CIUDADANA, siempre la última (solo si se pertenece a un radioclub)
        if (radioclub.radioclubConfigurado()) cbSelectorActividad.addItem(NOMBRE_VISTA_CB);
    }

    // Títulos de grupo en el desplegable de actividades (no se pueden elegir)
    private static final String TITULO_ACTIVIDADES = "ACTIVIDADES";
    private static final String TITULO_ACTIVIDADES_RC = "ACTIVIDADES RADIOCLUB";

    private static boolean esTituloDesplegable(Object o) {
        return TITULO_ACTIVIDADES.equals(o) || TITULO_ACTIVIDADES_RC.equals(o);
    }

    /** Modelo del desplegable de actividades: los títulos de grupo no se pueden elegir. */
    private static class ModeloDesplegableActividades extends DefaultComboBoxModel<String> {
        @Override public void setSelectedItem(Object o) {
            if (esTituloDesplegable(o)) {
                // Con el ratón, pinchar en un título no hace nada.
                // Con las flechas del teclado, se salta el título y se pasa a la siguiente actividad.
                if (!(EventQueue.getCurrentEvent() instanceof java.awt.event.KeyEvent)) return;
                int i = getIndexOf(o), actual = getIndexOf(getSelectedItem());
                int paso = actual > i ? -1 : 1;
                Object destino = null;
                for (int j = i + paso; j >= 0 && j < getSize(); j += paso) {
                    if (!esTituloDesplegable(getElementAt(j))) { destino = getElementAt(j); break; }
                }
                if (destino == null) return;
                o = destino;
            }
            super.setSelectedItem(o);
        }
    }

    /** Dibujo del desplegable de actividades: títulos en otro color y actividades un poco metidas hacia dentro. */
    private static ListCellRenderer<? super String> rendererDesplegableActividades(ListCellRenderer<? super String> base) {
        return (list, value, index, isSelected, cellHasFocus) -> {
            boolean titulo = esTituloDesplegable(value);
            Component c = base.getListCellRendererComponent(list, value, index, isSelected && !titulo, cellHasFocus && !titulo);
            if (c instanceof JLabel l && index >= 0) {
                if (titulo) {
                    l.setFont(l.getFont().deriveFont(Font.BOLD));
                    l.setForeground(new Color(150, 170, 200));
                } else if (value != null && value.matches("\\d+ - .*")) {
                    l.setText("      " + value);   // actividad: debajo de su título
                }
            }
            return c;
        };
    }

    private void seleccionarActividadEnDesplegable(int numeralBuscado) {
        for (int i = 0; i < cbSelectorActividad.getItemCount(); i++) {
            String item = cbSelectorActividad.getItemAt(i);
            if (item != null && item.startsWith(numeralBuscado + " - ")) {
                cbSelectorActividad.setSelectedIndex(i);
                return;
            }
        }
    }

    private JButton btnSincronizarRC;

    /** Archivo → Sincronizar Contactos: mis ordenadores (si no está configurado, se ofrece configurarlo). */
    private void sincronizarContactosDesdeMenu() {
        String op = operadorActual == null ? "" : operadorActual.trim();
        if (op.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Elige primero un operador.", "Sincronizar ordenadores", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!radioclub.syncOpActivo()) {
            int r = JOptionPane.showConfirmDialog(this, "Todavía no has configurado dónde se sincronizan los contactos de " + op + " entre tus ordenadores\n"
                            + "(una carpeta de la nube o un FTP).\n\n¿Configurarlo ahora?",
                    "Sincronizar ordenadores", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r == JOptionPane.YES_OPTION) radioclub.configurarSyncMisOrdenadores(this, op);
            return;
        }
        radioclub.sincronizarOrdenadoresConVentana();
    }

    /** Botón "Sincronizar" del LOG Radioclub. */
    private void sincronizarDesdeBoton() {
        if (!radioclub.syncActivo()) {
            int r = JOptionPane.showConfirmDialog(this, "Este ordenador todavía no comparte con el radioclub.\n\n"
                            + "¿Abrir Configurar Radioclub para unirte con la clave de invitación?",
                    "Sincronizar", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r == JOptionPane.YES_OPTION) radioclub.abrirDialogoConfigRadioclub();
            return;
        }
        btnSincronizarRC.setEnabled(false);
        btnSincronizarRC.setText("Sincronizando…");
        radioclub.actualizarEstadoSync("Radioclub: sincronizando…", TEMA_TEXTO);
        Thread t = new Thread(() -> {
            try {
                radioclub.sincronizarRadioclub();
            } finally {
                SwingUtilities.invokeLater(() -> {
                    btnSincronizarRC.setEnabled(true);
                    btnSincronizarRC.setText("Sincronizar");
                    cargarQSOsDesdeBaseDatos();
                });
            }
        }, "SincronizarBoton");
        t.setDaemon(true);
        t.start();
    }

    void actualizarVisibilidadBotonDiploma() {
        if (btnGenerarDiploma == null || cbSelectorActividad == null) return;
        Object sel = cbSelectorActividad.getSelectedItem();
        boolean esLogPrincipal = (sel == null) || "LOG Principal".equals(sel.toString())
                || NOMBRE_VISTA_CB.equals(sel.toString()) || NOMBRE_VISTA_RC.equals(sel.toString());
        btnGenerarDiploma.setVisible(!esLogPrincipal);
        // Los botones "Sincronizar" ya no se muestran: se sincroniza desde el menú Archivo
        if (btnSincronizarRC != null) btnSincronizarRC.setVisible(false);
        if (radioclub.btnSincronizarOp != null) radioclub.btnSincronizarOp.setVisible(false);

        // En BANDA CIUDADANA no se muestra el formulario de contactos, solo el botón de agregar
        boolean esCB = NOMBRE_VISTA_CB.equals(sel == null ? "" : sel.toString());
        if (panelFormularioQso != null) panelFormularioQso.setVisible(!esCB);
        if (btnAgregarCB != null) btnAgregarCB.setVisible(esCB);
        if (btnActualizarCB != null) btnActualizarCB.setVisible(esCB);
        if (btnEditarCB != null) btnEditarCB.setVisible(esCB);
        if (btnExportarCB != null) btnExportarCB.setVisible(esCB);
        if (btnImportarCB != null) btnImportarCB.setVisible(esCB);
        if (btnActualizarContactos != null) btnActualizarContactos.setVisible(!esCB);
        // Título de la tabla y sitio del buscador
        if (scrollTabla != null) {
            scrollTabla.setBorder(BorderFactory.createTitledBorder(esCB ? "Directorio de Indicativos"
                    : "Log de QSOs (BD: " + DatabaseManager.obtenerRutaActiva() + ")"));
        }
        if (filaBuscar != null && zonaTabla != null && huecoBuscarCB != null) {
            java.awt.Container destino = esCB ? huecoBuscarCB : cluster.filaSobreTabla;
            if (filaBuscar.getParent() != destino) {
                if (filaBuscar.getParent() != null) filaBuscar.getParent().remove(filaBuscar);
                if (esCB) huecoBuscarCB.add(filaBuscar, BorderLayout.CENTER);
                else cluster.filaSobreTabla.add(filaBuscar, BorderLayout.CENTER);
                filaBuscar.setOpaque(!esCB);
                zonaTabla.revalidate();
                huecoBuscarCB.revalidate();
            }
        }
        // En el Directorio de Indicativos la banda es CB
        if (esCB && cbBanda != null && !cat.esBandaCB() && cat.comboContiene(cbBanda, "CB")) {
            cbBanda.setSelectedItem("CB");
            cat.refrescarFrontal();
        }

        if (esCB && lblAvisoCat != null) lblAvisoCat.setVisible(false);
        revalidate();
        repaint();
    }

    private JPanel crearPanelEntradaQSO() {
        JPanel panelPrincipal = new JPanel(new BorderLayout(5, 5));
        panelPrincipal.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Los controles se crean aquí y se colocan más abajo según el diseño (cabecera + actividad + formulario)
        JPanel panelIzquierdo = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));

        btnOperador = new JButton("Operador: " + (operadorActual == null || operadorActual.isEmpty() ? "(sin configurar)" : operadorActual));
        btnOperador.setFocusable(false);
        btnOperador.addActionListener(e -> abrirDialogoSeleccionOperador());
        panelIzquierdo.add(btnOperador);

        // Modo radioclub: casilla + desplegable para elegir a mano el indicativo (CB, HF o especial)
        chkModoRadioclub = new JCheckBox(radioclub.nombreRadioclub());
        chkModoRadioclub.setFocusable(false);
        chkModoRadioclub.addActionListener(e -> radioclub.alternarModoRadioclub());
        lblIndicativoRC = new JLabel("Indicativo:");
        cbIndicativoRC = new JComboBox<>();
        cbIndicativoRC.setFocusable(false);
        cbIndicativoRC.addActionListener(e -> {
            Object sel = cbIndicativoRC.getSelectedItem();
            if (sel == null) return;
            String s = sel.toString();
            int pos = s.indexOf(":");
            if (pos > 0) ConfigApp.put("RADIOCLUB_IND_ELEGIDO", s.substring(0, pos));
        });
        radioclub.recargarDesplegableIndicativoRC();
        panelIzquierdo.add(chkModoRadioclub);
        panelIzquierdo.add(lblIndicativoRC);
        panelIzquierdo.add(cbIndicativoRC);
        radioclub.actualizarControlesRadioclub();

        panelIzquierdo.add(new JLabel("Selecciona Actividad:"));
        cbSelectorActividad = new JComboBox<>(new ModeloDesplegableActividades());
        cbSelectorActividad.setRenderer(rendererDesplegableActividades(cbSelectorActividad.getRenderer()));
        actualizarDesplegableActividades();
        cbSelectorActividad.addActionListener(e -> {
            actualizarVisibilidadBotonDiploma();
            // Al cambiar de LOG o actividad se borra el contacto que estuviera a medio escribir
            // (para no guardarlo por error en otra actividad)
            limpiarContactoAlCambiarActividad();
            // y el mapa y la bandera vuelven a lo que hay en el formulario (vacío: solo mi QTH y sin bandera)
            SwingUtilities.invokeLater(this::programarMapaDesdeFormulario);

            bandaActividadReq = "";
            modoActividadReq = "";
            avisoCatMostrado = false;
            if (lblAvisoCat != null) lblAvisoCat.setVisible(false);

            int numeralSel = obtenerNumeralDeActividadDesdeSeleccion();
            radioclub.actualizarModoRadioclubSegunSeleccion();
            aplicarRestriccionBandaModoActividad(numeralSel);
            if (numeralSel > 0) {
                String tipo = obtenerTipoActividadPorNumeral(numeralSel);
                if ("SIMPLE".equals(tipo)) {
                    String bandaFija = obtenerBandaSimpleDeActividad(numeralSel);
                    String modoFijo = obtenerModoSimpleDeActividad(numeralSel);
                    bandaActividadReq = bandaFija == null ? "" : bandaFija;
                    modoActividadReq = modoFijo == null ? "" : modoFijo;
                    if (!bandaFija.isEmpty()) {
                        // La BD guarda la banda en mayúsculas ("40M"); el desplegable usa "40m"
                        String bandaCanonica = normalizarOpcion(bandaFija, BANDAS);
                        cbBanda.setSelectedItem(bandaCanonica);
                        // Frecuencia estándar de la banda, aunque esa banda ya estuviera seleccionada
                        InfoBanda infoBandaAct = CONFIG_BANDAS.get(bandaCanonica);
                        if (infoBandaAct != null) {
                            txtFreqTx.setText(formatearHzDesdeMHz(infoBandaAct.freq));
                            txtFreqRx.setText(formatearHzDesdeMHz(infoBandaAct.freq));
                        }
                    }
                    if (!modoFijo.isEmpty()) cbModo.setSelectedItem(modoFijo);

                    // Si todos los contactos de la actividad tienen la misma frecuencia, esa es la de la actividad
                    String freqActividad = obtenerFrecuenciaComunDeActividad(numeralSel);
                    if (!freqActividad.isEmpty()) {
                        txtFreqTx.setText(formatearHzDesdeMHz(freqActividad));
                        txtFreqRx.setText(formatearHzDesdeMHz(freqActividad));
                    }
                    // Al elegir la actividad: también la emisora (si el CAT está conectado)
                    if (!rehaciendoDesplegableActividades) enviarFormularioAEmisora();
                } else if ("PUNTOS".equals(tipo) && !rehaciendoDesplegableActividades) {
                    // Actividad por puntos: la banda más alta que puntúa y su modo
                    ponerBandaModoInicialActividad(true, obtenerNombreInternoDeActividad(numeralSel), "", "");
                }
            }

            cargarQSOsDesdeBaseDatos();
        });
        panelIzquierdo.add(cbSelectorActividad);

        btnCatToggle = new JButton("Conectar CAT");
        btnCatToggle.setFocusable(false);
        btnCatToggle.setBackground(new Color(0, 150, 0));
        btnCatToggle.setForeground(Color.WHITE);
        btnCatToggle.addActionListener(e -> alternarConexionCat());
        panelIzquierdo.add(btnCatToggle);

        lblAvisoCat = new JLabel();
        lblAvisoCat.setForeground(new Color(200, 0, 0));
        lblAvisoCat.setFont(lblAvisoCat.getFont().deriveFont(Font.BOLD));
        lblAvisoCat.setVisible(false);
        panelIzquierdo.add(lblAvisoCat);

        JPanel panelDerecho = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        btnGenerarDiploma = new JButton("Configurar Diploma");
        btnGenerarDiploma.setFocusable(false);
        btnGenerarDiploma.addActionListener(e -> generarDiploma());
        btnAgregarCB = new JButton("Agregar contacto");
        btnAgregarCB.setFocusable(false);
        btnAgregarCB.setVisible(false);
        btnAgregarCB.addActionListener(e -> agregarIndicativoCB());
        btnActualizarCB = new JButton("Actualizar base de datos");
        btnActualizarCB.setFocusable(false);
        btnActualizarCB.setVisible(false);
        btnActualizarCB.addActionListener(e -> actualizarListaBandaCiudadana());
        panelDerecho.add(btnActualizarCB);
        panelDerecho.add(btnAgregarCB);
        // "Generar Diploma" ya no va aquí arriba: está en el formulario, a la derecha del Grid

        actualizarVisibilidadBotonDiploma();

        JPanel formGrid = new JPanel(new GridBagLayout());
        formGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        txtIndicativo = new JTextField(10);
        txtIndicativo.setFont(new Font("Arial", Font.BOLD, 14));

        txtNombre = new JTextField(15);
        txtQth = new JTextField(12);
        txtGrid = new JTextField(8);
        txtComentario = new JTextField(30);
        txtComentario.setToolTipText("Nota libre de este contacto. Se queda en tu log (y en el del radioclub); no se envía a QRZ ni a los demás servicios.");

        aplicarFiltroMayusculas(txtIndicativo);
        aplicarFiltroMayusculas(txtComentario);
        aplicarFiltroMayusculas(txtNombre);
        aplicarFiltroMayusculas(txtQth);
        aplicarFiltroMayusculas(txtGrid);

        final javax.swing.Timer[] timerDebounce = new javax.swing.Timer[1];

        txtIndicativo.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { dispararBusqueda(); }
            @Override
            public void removeUpdate(DocumentEvent e) { dispararBusqueda(); }
            @Override
            public void changedUpdate(DocumentEvent e) { dispararBusqueda(); }

            private void dispararBusqueda() {
                String call = txtIndicativo.getText().trim();
                if (lblQrzIcon != null) lblQrzIcon.setVisible(false);

                if (timerDebounce[0] != null && timerDebounce[0].isRunning()) {
                    timerDebounce[0].stop();
                }

                if (call.length() < 3) {
                    txtNombre.setText("");
                    txtQth.setText("");
                    txtGrid.setText("");
                    alternarModoNombre(false);
                    return;
                }

                timerDebounce[0] = new javax.swing.Timer(350, evt -> buscarDatosIndicativo(call));
                timerDebounce[0].setRepeats(false);
                timerDebounce[0].start();
            }
        });

        txtIndicativo.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                String call = txtIndicativo.getText().trim();
                if (call.length() >= 3) {
                    if (timerDebounce[0] != null && timerDebounce[0].isRunning()) {
                        timerDebounce[0].stop();
                    }
                    buscarDatosIndicativo(call);
                }
            }
        });

        cbHoraModo = new JComboBox<>(new String[]{"UTC", "LOCAL"});
        try {
            String m = java.util.prefs.Preferences.userRoot().node("libroguardia").get("hora_modo", "UTC");
            cbHoraModo.setSelectedItem("LOCAL".equals(m) ? "LOCAL" : "UTC");
        } catch (Exception ignored) { }
        horaModoLocalPrevio = horaLocal();
        String[] ahoraIni = ahoraSegunModo();
        txtFecha = new JTextField(ahoraIni[0], 8);
        txtHora = new JTextField(ahoraIni[1], 6);
        cbHoraModo.addActionListener(ev -> {
            boolean local = horaLocal();
            if (local == horaModoLocalPrevio) return;
            String[] c = convertirFechaHora(txtFecha.getText(), txtHora.getText(), local);
            txtFecha.setText(c[0]);
            txtHora.setText(c[1]);
            horaModoLocalPrevio = local;
            try {
                java.util.prefs.Preferences.userRoot().node("libroguardia").put("hora_modo", local ? "LOCAL" : "UTC");
            } catch (Exception ignored) { }
            cargarQSOsDesdeBaseDatos();
        });

        cbBanda = new JComboBox<>(BANDAS);
        cbModo = new JComboBox<>(MODOS);
        txtFreqTx = new ControlEmisora.CampoFrecuencia();
        txtFreqRx = new ControlEmisora.CampoFrecuencia();
        txtRstSent = new JTextField("59", 4);
        txtRstRcvd = new JTextField("59", 4);
        // El RST solo admite números (y el signo de los informes en dB de FT8/FT4, como -08)
        ((AbstractDocument) txtRstSent.getDocument()).setDocumentFilter(new FiltroTexto("[0-9+-]", false));
        ((AbstractDocument) txtRstRcvd.getDocument()).setDocumentFilter(new FiltroTexto("[0-9+-]", false));

        cbBanda.addActionListener(e -> {
            String bandaSeleccionada = (String) cbBanda.getSelectedItem();
            if (bandaSeleccionada != null) {
                InfoBanda info = CONFIG_BANDAS.get(bandaSeleccionada);
                // Si la banda la pone la frecuencia escrita, no se pisan la frecuencia ni el modo
                if (info != null && !cat.bandaPuestaPorFrecuencia) {
                    cbModo.setSelectedItem(info.modo);
                    txtFreqTx.setText(formatearHzDesdeMHz(info.freq));
                    txtFreqRx.setText(formatearHzDesdeMHz(info.freq));
                }
                if ("CB".equalsIgnoreCase(bandaSeleccionada) && txtIndicativo != null
                        && !txtIndicativo.getText().trim().isEmpty()) {
                    buscarDatosIndicativo(txtIndicativo.getText().trim());
                }
            }
        });

        KeyAdapter enterListener = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    guardarQSO();
                }
            }
        };

        txtIndicativo.addKeyListener(enterListener);
        txtFecha.addKeyListener(enterListener);
        txtHora.addKeyListener(enterListener);
        // Reloj: la hora y la fecha avanzan solas cada segundo, salvo que el usuario las escriba a mano
        KeyAdapter marcaHoraManual = new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                if (!Character.isISOControl(c) || c == '\b' || c == 127) horaEscritaAMano = true;
            }
        };
        txtHora.addKeyListener(marcaHoraManual);
        txtFecha.addKeyListener(marcaHoraManual);
        new javax.swing.Timer(1000, ev -> {
            if (horaEscritaAMano || txtHora.isFocusOwner() || txtFecha.isFocusOwner()) return;
            String[] a = ahoraSegunModo();
            if (!a[1].equals(txtHora.getText())) txtHora.setText(a[1]);
            if (!a[0].equals(txtFecha.getText())) txtFecha.setText(a[0]);
        }).start();
        txtFreqTx.addKeyListener(enterListener);
        txtFreqRx.addKeyListener(enterListener);
        txtRstSent.addKeyListener(enterListener);
        txtRstRcvd.addKeyListener(enterListener);
        txtNombre.addKeyListener(enterListener);
        txtQth.addKeyListener(enterListener);
        txtGrid.addKeyListener(enterListener);
        txtComentario.addKeyListener(enterListener);

        // ---- Distribución del formulario (según el diseño) ----
        // Fila 1: CALLSIGN, Fecha | Hora
        // Fila 2: Nombre | QTH | Grid | icono QRZ
        // Fila 3: Banda, Modo | Freq TX, Freq RX | RST S, RST R | Generar Diploma
        cardLayoutNombre = new CardLayout();
        panelNombreContenedor = new JPanel(cardLayoutNombre);
        panelNombreContenedor.add(txtNombre, CARD_TEXTO);

        cbNombres = new JComboBox<>();
        cbNombres.setFont(new Font("Arial", Font.PLAIN, 12));
        cbNombres.addActionListener(e -> seleccionarOperadorDelCombo());
        panelNombreContenedor.add(cbNombres, CARD_COMBO);
        cardLayoutNombre.show(panelNombreContenedor, CARD_TEXTO);

        // Bandera del país del corresponsal
        lblBandera = new JLabel("", SwingConstants.CENTER);
        lblBandera.setVerticalTextPosition(SwingConstants.BOTTOM);
        lblBandera.setHorizontalTextPosition(SwingConstants.CENTER);
        lblBandera.setFont(new Font("Arial", Font.BOLD, 8));
        lblBandera.setPreferredSize(new Dimension(150, 54));

        lblQrzIcon = new JLabel();
        lblQrzIcon.setVisible(false);
        lblQrzIcon.setCursor(new Cursor(Cursor.HAND_CURSOR));
        lblQrzIcon.setToolTipText("Ver datos en QRZ.com");
        lblQrzIcon.setHorizontalAlignment(SwingConstants.LEFT);

        // Botón propio "QRZ.com" (dibujado por el programa: no se usa el logotipo de QRZ.com)
        IconoBotonQrz iconoQrz = new IconoBotonQrz();
        lblQrzIcon.setIcon(iconoQrz);
        lblQrzIcon.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { iconoQrz.encima = true; lblQrzIcon.repaint(); }
            @Override public void mouseExited(MouseEvent e) { iconoQrz.encima = false; iconoQrz.pulsado = false; lblQrzIcon.repaint(); }
            @Override public void mousePressed(MouseEvent e) { iconoQrz.pulsado = true; lblQrzIcon.repaint(); }
            @Override public void mouseReleased(MouseEvent e) { iconoQrz.pulsado = false; lblQrzIcon.repaint(); }
        });

        lblQrzIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                abrirPaginaQrz();
            }
        });


        // El mapa se actualiza al cambiar el Grid o el QTH del formulario
        DocumentListener cambioPosicion = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { programarMapaDesdeFormulario(); }
            @Override public void removeUpdate(DocumentEvent e) { programarMapaDesdeFormulario(); }
            @Override public void changedUpdate(DocumentEvent e) { programarMapaDesdeFormulario(); }
        };
        txtGrid.getDocument().addDocumentListener(cambioPosicion);
        txtQth.getDocument().addDocumentListener(cambioPosicion);
        txtQth.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { programarMapaDesdeFormulario(); }   // ahora sí se busca el QTH
        });
        txtIndicativo.getDocument().addDocumentListener(cambioPosicion);   // bandera del país
        // Cuadrícula de bandas y modos trabajados: sigue al indicativo que se escribe
        // (si la casilla se queda vacía, vuelve a mostrar el contacto seleccionado en el log)
        txtIndicativo.getDocument().addDocumentListener(new DocumentListener() {
            private void ver() {
                String t = txtIndicativo.getText().trim();
                panelTrabajado.mostrar(t.isEmpty() ? indicativoFilaSeleccionada() : t);
            }
            @Override public void insertUpdate(DocumentEvent e) { ver(); }
            @Override public void removeUpdate(DocumentEvent e) { ver(); }
            @Override public void changedUpdate(DocumentEvent e) { ver(); }
        });

        // ================= PANEL UNIFICADO ("frontal de emisora") =================
        // ---- Cabecera: CAT, operador, actividad | hora y fecha ----
        cat.lblActividadCab = new JLabel("Actividad:");
        cat.lblActividadCab.setFont(new Font("Arial", Font.BOLD, 13));
        cbSelectorActividad.setPreferredSize(new Dimension(150, cbSelectorActividad.getPreferredSize().height));
        cbSelectorActividad.setMinimumSize(new Dimension(110, cbSelectorActividad.getPreferredSize().height));
        JPanel cabIzq = new JPanel(new GridBagLayout());
        cabIzq.setOpaque(false);
        GridBagConstraints gcab = new GridBagConstraints();
        gcab.insets = new Insets(2, 0, 2, 8);
        gcab.anchor = GridBagConstraints.WEST;
        cat.btnCascada = new JToggleButton("Cascada");
        cat.btnCascada.setFont(btnOperador.getFont());
        cat.btnCascada.setFocusable(false);
        cat.btnCascada.setToolTipText("Ver u ocultar la cascada del RTL-SDR (salida de FI de la emisora)");
        cat.btnCascada.addActionListener(e -> cat.alternarCascada(cat.btnCascada.isSelected()));
        for (JComponent c : new JComponent[]{btnCatToggle, cat.btnCascada, btnOperador, cat.lblActividadCab}) cabIzq.add(c, gcab);
        if ("1".equals(ConfigApp.get("CASCADA_VISIBLE", "0"))) {
            SwingUtilities.invokeLater(() -> { cat.btnCascada.setSelected(true); cat.alternarCascada(true); });
        }
        gcab.weightx = 1.0;
        gcab.fill = GridBagConstraints.HORIZONTAL;
        cabIzq.add(cbSelectorActividad, gcab);
        JPanel cabDer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 2));
        cabDer.setOpaque(false);
        cabDer.add(cat.rotuloLcd("Hora"));
        cabDer.add(txtHora);
        cabDer.add(cbHoraModo);
        cabDer.add(Box.createHorizontalStrut(6));
        cabDer.add(cat.rotuloLcd("Fecha"));
        cabDer.add(txtFecha);
        JPanel cabecera = new JPanel(new BorderLayout());
        cabecera.setOpaque(false);
        cabecera.add(cabIzq, BorderLayout.CENTER);
        cabecera.add(cabDer, BorderLayout.EAST);

        // ---- Pantalla grande: TX, RX, banda y modo, estado de la emisora ----
        txtFreqTx.removeKeyListener(enterListener);   // en la pantalla, Intro lo gestiona crearPantallaLcd
        JPanel lcd = cat.crearPantallaLcd();

        // ---- Contacto ----
        txtIndicativo.setFont(new Font("Arial", Font.BOLD, 16));
        txtIndicativo.setColumns(10);
        panelNombreContenedor.setPreferredSize(new Dimension(190, txtNombre.getPreferredSize().height));
        txtQth.setPreferredSize(new Dimension(200, txtQth.getPreferredSize().height));
        txtGrid.setColumns(6);
        JPanel contacto = new JPanel(new GridBagLayout());
        contacto.setOpaque(false);
        GridBagConstraints gk = new GridBagConstraints();
        gk.anchor = GridBagConstraints.NORTHWEST;
        gk.insets = new Insets(0, 0, 6, 0);
        gk.gridx = 0; gk.gridy = 0;
        contacto.add(cat.rotuloSeccion("CONTACTO"), gk);
        gk.gridy = 1;
        JPanel filaC1 = grupoFormulario(
                crearElementoFormulario("CALLSIGN", txtIndicativo),
                crearElementoFormulario("Nombre", panelNombreContenedor));
        contacto.add(filaC1, gk);
        gk.gridy = 2;
        JPanel filaC2 = grupoFormulario(
                crearElementoFormulario("QTH (Ciudad)", txtQth),
                crearElementoFormulario("Grid", txtGrid),
                crearElementoFormulario("RST S", txtRstSent),
                crearElementoFormulario("RST R", txtRstRcvd));
        // Debajo de QTH, Grid y RST: el comentario del contacto (en la misma celda, para no mover lo demás)
        txtComentario.setPreferredSize(new Dimension(340, txtComentario.getPreferredSize().height));
        JPanel filaC3 = grupoFormulario(crearElementoFormulario("Comentario", txtComentario));
        JPanel filasC23 = new JPanel();
        filasC23.setOpaque(false);
        filasC23.setLayout(new BoxLayout(filasC23, BoxLayout.Y_AXIS));
        filaC2.setAlignmentX(Component.LEFT_ALIGNMENT);
        filaC3.setAlignmentX(Component.LEFT_ALIGNMENT);
        filasC23.add(filaC2);
        filasC23.add(Box.createVerticalStrut(6));
        filasC23.add(filaC3);
        contacto.add(filasC23, gk);

        // Mando de sintonía (como el dial de la emisora), a la derecha de los datos del contacto
        cat.dialSintonia = new ControlEmisora.DialEmisora(cat::moverDial);
        GridBagConstraints gd = new GridBagConstraints();
        gd.gridx = 1; gd.gridy = 0; gd.gridheight = 3;
        gd.insets = new Insets(0, 14, 0, 0);
        gd.anchor = GridBagConstraints.CENTER;
        contacto.add(cat.dialSintonia, gd);
        // Debajo del dial: DOWN (izquierda) y UP (derecha), 10 Hz por pulsación; dejándolos pulsados no paran
        JPanel filaFino = new JPanel(new GridLayout(1, 2, 6, 0));
        filaFino.setOpaque(false);
        filaFino.add(cat.crearBotonFino("DOWN", -1));
        filaFino.add(cat.crearBotonFino("UP", +1));
        // Debajo de DOWN y UP: SEND (pasa la emisora a transmisión, como su tecla SEND/MOX),
        // tan ancho como DOWN y UP juntos, a la altura de los botones VFO A, VFO B...
        cat.btnSend = cat.crearBotonSend();
        cat.btnSend.setPreferredSize(new Dimension(58, 30));
        JPanel huecoSend = new JPanel(new BorderLayout());
        huecoSend.setOpaque(false);
        huecoSend.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        huecoSend.add(cat.btnSend, BorderLayout.NORTH);
        JPanel bloqueDial = new JPanel(new BorderLayout());
        bloqueDial.setOpaque(false);
        bloqueDial.add(filaFino, BorderLayout.NORTH);
        bloqueDial.add(huecoSend, BorderLayout.CENTER);
        // SEND a la misma altura (y con el mismo alto) que la fila de VFO A, VFO B...
        Runnable alinearSend = () -> {
            if (cat.btnVfoA == null || !cat.btnVfoA.isShowing() || !bloqueDial.isShowing()) return;
            Point pv = SwingUtilities.convertPoint(cat.btnVfoA.getParent(), cat.btnVfoA.getLocation(), bloqueDial);
            int hueco = Math.max(6, pv.y - filaFino.getHeight());
            int alto = Math.max(24, cat.btnVfoA.getHeight());
            java.awt.Insets ins = huecoSend.getBorder().getBorderInsets(huecoSend);
            if (ins.top == hueco && cat.btnSend.getPreferredSize().height == alto) return;
            huecoSend.setBorder(BorderFactory.createEmptyBorder(hueco, 0, 0, 0));
            cat.btnSend.setPreferredSize(new Dimension(58, alto));
            bloqueDial.revalidate();
        };
        java.awt.event.ComponentAdapter vigilarSend = new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { SwingUtilities.invokeLater(alinearSend); }
            @Override public void componentMoved(java.awt.event.ComponentEvent e) { SwingUtilities.invokeLater(alinearSend); }
            @Override public void componentShown(java.awt.event.ComponentEvent e) { SwingUtilities.invokeLater(alinearSend); }
        };
        bloqueDial.addComponentListener(vigilarSend);
        SwingUtilities.invokeLater(() -> { if (cat.btnVfoA != null) cat.btnVfoA.addComponentListener(vigilarSend); alinearSend.run(); });
        GridBagConstraints gf = new GridBagConstraints();
        gf.gridx = 1; gf.gridy = 3;
        gf.insets = new Insets(4, 14, 0, 0);
        gf.anchor = GridBagConstraints.NORTH;
        gf.fill = GridBagConstraints.HORIZONTAL;
        contacto.add(bloqueDial, gf);

        // ---- Radioclub (logo, casilla e indicativo) y diploma ----
        lblLogoRC = new JLabel();
        lblLogoRC.setHorizontalAlignment(SwingConstants.CENTER);
        lblLogoRC.setPreferredSize(new Dimension(TAM_LOGO, TAM_LOGO));
        lblLogoRC.setMinimumSize(new Dimension(TAM_LOGO, TAM_LOGO));
        radioclub.cargarLogoRadioclub();
        JPanel colRC = new JPanel();
        colRC.setOpaque(false);
        colRC.setLayout(new BoxLayout(colRC, BoxLayout.Y_AXIS));
        JPanel filaIndRC = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        filaIndRC.setOpaque(false);
        filaIndRC.add(lblIndicativoRC);
        filaIndRC.add(cbIndicativoRC);
        chkModoRadioclub.setOpaque(false);
        chkModoRadioclub.setFont(new Font("Arial", Font.BOLD, 13));
        chkModoRadioclub.setAlignmentX(Component.LEFT_ALIGNMENT);
        filaIndRC.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Ya no hay casilla ni desplegable: el radioclub se elige al crear la actividad (Nueva Actividad)
        lblNombreRC = new JLabel(radioclub.nombreRadioclub());
        lblNombreRC.setFont(new Font("Arial", Font.BOLD, 13));
        lblNombreRC.setForeground(TEMA_TEXTO);
        lblIndicativoActivoRC = new JLabel(" ");
        lblIndicativoActivoRC.setFont(new Font("Arial", Font.BOLD, 12));
        lblIndicativoActivoRC.setForeground(TEMA_AMBAR);
        lblIndicativoActivoRC2 = new JLabel(" ");   // segunda línea (en el LOG Radioclub: el indicativo de CB)
        lblIndicativoActivoRC2.setFont(new Font("Arial", Font.BOLD, 12));
        lblIndicativoActivoRC2.setForeground(TEMA_AMBAR);
        // Logo | casilla del radioclub (arriba) e indicativo (abajo) | "Generar Diploma" a la altura del
        // indicativo y centrado en el hueco que queda hasta los botones de la emisora
        JPanel filaRC = new JPanel(new GridBagLayout());
        filaRC.setOpaque(false);
        GridBagConstraints gr = new GridBagConstraints();
        gr.gridx = 0; gr.gridy = 0; gr.gridheight = 2;
        gr.insets = new Insets(0, 0, 0, 8);
        gr.anchor = GridBagConstraints.WEST;
        filaRC.add(lblLogoRC, gr);
        // Nombre del radioclub (y debajo el indicativo en uso) centrado a la altura del logotipo
        JPanel textoRC = new JPanel();
        textoRC.setOpaque(false);
        textoRC.setLayout(new BoxLayout(textoRC, BoxLayout.Y_AXIS));
        lblNombreRC.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblIndicativoActivoRC.setAlignmentX(Component.LEFT_ALIGNMENT);
        textoRC.add(lblNombreRC);
        textoRC.add(lblIndicativoActivoRC);
        lblIndicativoActivoRC2.setAlignmentX(Component.LEFT_ALIGNMENT);
        textoRC.add(lblIndicativoActivoRC2);
        // Pinchando (botón izquierdo) en el logo, el nombre o los indicativos del radioclub se abre su página web
        for (JLabel l : new JLabel[]{lblLogoRC, lblNombreRC, lblIndicativoActivoRC, lblIndicativoActivoRC2}) {
            l.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    boolean hay = !ConfigApp.get("RADIOCLUB_WEB", "").trim().isEmpty();
                    l.setCursor(hay ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                    l.setToolTipText(hay ? "Abrir la web del radioclub: " + ConfigApp.get("RADIOCLUB_WEB", "").trim() : null);
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) abrirWebRadioclub();
                }
            });
        }
        // "Generar Diploma" debajo de los datos del radioclub. Va dentro de un hueco que siempre ocupa su
        // sitio: así el logo y el radioclub quedan en el mismo lugar en todos los LOG, se vea o no el botón
        JPanel huecoDiploma = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        huecoDiploma.setOpaque(false);
        huecoDiploma.add(btnGenerarDiploma);
        // En el LOG Radioclub, en ese mismo sitio: "Sincronizar" (comparte ya con el radioclub)
        btnSincronizarRC = new JButton("Sincronizar");
        btnSincronizarRC.setFocusable(false);
        btnSincronizarRC.setToolTipText("Envía lo nuevo de este ordenador y recoge lo de los demás socios del radioclub");
        btnSincronizarRC.addActionListener(e -> sincronizarDesdeBoton());
        btnSincronizarRC.setVisible(false);
        huecoDiploma.add(btnSincronizarRC);
        // En el LOG Principal, en ese mismo sitio: "Sincronizar" (mis ordenadores), si el operador lo tiene configurado
        radioclub.btnSincronizarOp = new JButton("Sincronizar");
        radioclub.btnSincronizarOp.setFocusable(false);
        radioclub.btnSincronizarOp.setToolTipText("Envía lo nuevo de este ordenador y recoge lo de tus otros ordenadores");
        radioclub.btnSincronizarOp.addActionListener(e -> radioclub.sincronizarMisOrdenadoresEnSegundoPlano());
        radioclub.btnSincronizarOp.setVisible(false);
        huecoDiploma.add(radioclub.btnSincronizarOp);
        Dimension tamHueco = new Dimension(Math.max(btnGenerarDiploma.getPreferredSize().width, Math.max(btnSincronizarRC.getPreferredSize().width, radioclub.btnSincronizarOp.getPreferredSize().width)),
                Math.max(btnGenerarDiploma.getPreferredSize().height, Math.max(btnSincronizarRC.getPreferredSize().height, radioclub.btnSincronizarOp.getPreferredSize().height)));
        huecoDiploma.setPreferredSize(tamHueco);
        huecoDiploma.setMaximumSize(tamHueco);
        huecoDiploma.setAlignmentX(Component.LEFT_ALIGNMENT);
        textoRC.add(Box.createVerticalStrut(6));
        textoRC.add(huecoDiploma);
        gr.gridheight = 2;
        gr.gridx = 1; gr.gridy = 0;
        gr.anchor = GridBagConstraints.WEST;
        filaRC.add(textoRC, gr);
        // A la derecha del radioclub: el logo de QRZ.COM y debajo la bandera del país del corresponsal.
        // Van en un hueco de tamaño fijo: al aparecer o desaparecer no se mueve nada más.
        JPanel colQrzBandera = new JPanel();
        colQrzBandera.setOpaque(false);
        colQrzBandera.setLayout(new BoxLayout(colQrzBandera, BoxLayout.Y_AXIS));
        lblQrzIcon.setHorizontalAlignment(SwingConstants.CENTER);
        lblQrzIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblBandera.setAlignmentX(Component.CENTER_ALIGNMENT);
        int altoQrz = Math.max(22, lblQrzIcon.getIcon() == null ? 22 : lblQrzIcon.getIcon().getIconHeight());
        Dimension tamQrz = new Dimension(130, altoQrz);
        lblQrzIcon.setPreferredSize(tamQrz);
        lblQrzIcon.setMinimumSize(tamQrz);
        lblQrzIcon.setMaximumSize(tamQrz);
        Dimension tamBandera = new Dimension(130, 40);
        lblBandera.setPreferredSize(tamBandera);
        lblBandera.setMinimumSize(tamBandera);
        lblBandera.setMaximumSize(tamBandera);
        lblBandera.setIconTextGap(2);
        colQrzBandera.add(lblQrzIcon);
        colQrzBandera.add(Box.createVerticalStrut(6));
        colQrzBandera.add(lblBandera);
        Dimension tamCol = new Dimension(130, altoQrz + 50);
        colQrzBandera.setPreferredSize(tamCol);
        colQrzBandera.setMinimumSize(tamCol);
        gr.gridheight = 2;
        gr.gridx = 2; gr.gridy = 0;
        gr.weightx = 1.0;
        gr.anchor = GridBagConstraints.EAST;
        gr.insets = new Insets(0, 0, 0, 0);
        filaRC.add(colQrzBandera, gr);
        gk.gridy = 3;
        gk.fill = GridBagConstraints.HORIZONTAL;
        contacto.add(filaRC, gk);
        gk.fill = GridBagConstraints.NONE;
        gk.gridy = 4;
        gk.weighty = 1.0;
        contacto.add(Box.createVerticalGlue(), gk);

        // ---- Emisora: bandas, modos, VFO / SPLIT / RIT / XIT ----
        JPanel radio = cat.crearBotoneraEmisora();

        JPanel cuerpo = new JPanel(new BorderLayout(14, 0));
        cuerpo.setOpaque(false);
        cuerpo.add(contacto, BorderLayout.WEST);
        cuerpo.add(radio, BorderLayout.CENTER);
        panelFormularioQso = cuerpo;

        JPanel frontal = new JPanel(new BorderLayout(0, 6));
        frontal.setBackground(TEMA_FONDO);
        frontal.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(TEMA_LINEA, 1, true),
                BorderFactory.createEmptyBorder(4, 8, 8, 8)));
        JPanel arriba = new JPanel(new BorderLayout(0, 6));
        arriba.setOpaque(false);
        arriba.add(cabecera, BorderLayout.NORTH);
        // Pantalla de frecuencias y, debajo, la cascada del RTL-SDR (se ve con el botón "Cascada")
        JPanel lcdYCascada = new JPanel(new BorderLayout(0, 6));
        lcdYCascada.setOpaque(false);
        lcdYCascada.add(lcd, BorderLayout.NORTH);
        lcdYCascada.add(cat.crearCascada(), BorderLayout.CENTER);
        arriba.add(lcdYCascada, BorderLayout.CENTER);
        // Botones de la vista BANDA CIUDADANA (solo se ven en esa vista; el formulario se oculta)
        JPanel filaCB = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        filaCB.setOpaque(false);
        filaCB.add(btnActualizarCB);
        filaCB.add(btnAgregarCB);
        btnEditarCB = new JButton("Editar contacto");
        btnEditarCB.setFocusable(false);
        btnEditarCB.setVisible(false);
        btnEditarCB.setToolTipText("Cambiar el indicativo, el nombre o el QTH del contacto seleccionado (también con doble clic)");
        btnEditarCB.addActionListener(e -> editarContactoCB());
        filaCB.add(btnEditarCB);
        btnExportarCB = new JButton("Exportar base de datos");
        btnExportarCB.setFocusable(false);
        btnExportarCB.setVisible(esVistaBandaCiudadanaSeleccionada());
        btnExportarCB.setToolTipText("Guarda la lista BANDA CIUDADANA en un fichero .adi");
        btnExportarCB.addActionListener(e -> exportarListaBandaCiudadanaAdi());
        filaCB.add(btnExportarCB);
        btnImportarCB = new JButton("Importar base de datos");
        btnImportarCB.setFocusable(false);
        btnImportarCB.setVisible(esVistaBandaCiudadanaSeleccionada());
        btnImportarCB.setToolTipText("Añade a la lista BANDA CIUDADANA los indicativos de un fichero .adi");
        btnImportarCB.addActionListener(e -> importarListaBandaCiudadanaAdi());
        filaCB.add(btnImportarCB);

        JPanel centro = new JPanel(new BorderLayout());
        centro.setOpaque(false);
        // Debajo de los botones del Directorio de Indicativos va su buscador (solo en esa vista)
        huecoBuscarCB = new JPanel(new BorderLayout());
        huecoBuscarCB.setOpaque(false);
        JPanel arribaCB = new JPanel(new BorderLayout());
        arribaCB.setOpaque(false);
        arribaCB.add(filaCB, BorderLayout.NORTH);
        arribaCB.add(huecoBuscarCB, BorderLayout.CENTER);
        centro.add(arribaCB, BorderLayout.NORTH);
        centro.add(cuerpo, BorderLayout.CENTER);
        frontal.add(arriba, BorderLayout.NORTH);
        frontal.add(centro, BorderLayout.CENTER);

        // ---- MAPA a la derecha ----
        mapaQso = new MapaQso();
        JPanel marcoMapa = new JPanel(new BorderLayout());
        marcoMapa.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        marcoMapa.add(mapaQso, BorderLayout.CENTER);

        marcoMapa.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        // Con el cluster desplegado debajo del mapa (sin cascada), la parte izquierda no se estira: el hueco queda debajo
        JPanel envolturaFrontal = new JPanel(null) {
            @Override public Dimension getPreferredSize() { return frontal.getPreferredSize(); }
            @Override public Dimension getMinimumSize() { return frontal.getMinimumSize(); }
            @Override public void doLayout() {
                int alto = getHeight();
                if (cluster.clusterDesplegado && !cluster.cascadaAbierta()) alto = Math.min(alto, frontal.getPreferredSize().height);
                frontal.setBounds(0, 0, getWidth(), alto);
            }
        };
        envolturaFrontal.setOpaque(false);
        envolturaFrontal.add(frontal);
        panelPrincipal.add(envolturaFrontal, BorderLayout.CENTER);
        // La zona de la derecha (MAPA, emisoras, CANALES CB) no cambia de tamaño al abrir la cascada:
        // mide lo mismo que sin ella y se queda arriba (el hueco que sobra queda debajo)
        JPanel zonaDerecha = cat.crearZonaDerecha(marcoMapa);
        JPanel envolturaDerecha = new JPanel(null) {
            @Override public Dimension getPreferredSize() {
                // La misma altura en todas las actividades: la que tiene en el LOG (BANDA CIUDADANA es más baja a la izquierda)
                Dimension d = zonaDerecha.getPreferredSize();
                int alto = Math.max(d.height, cat.alturaZonaDerecha);
                // Cluster desplegado sin cascada: se hace sitio debajo del mapa
                if (cluster.clusterDesplegado && !cluster.cascadaAbierta()) alto += cluster.ALTO_CLUSTER_MAPA + 6;
                return new Dimension(d.width, alto);
            }
            @Override public Dimension getMinimumSize() { return zonaDerecha.getMinimumSize(); }
            @Override public void doLayout() {
                int h = getHeight();
                if (cat.panelCascada != null && cat.panelCascada.isVisible()) h -= cat.panelCascada.getHeight() + 6;
                else if (cluster.clusterDesplegado) h -= cluster.ALTO_CLUSTER_MAPA + 6;   // el hueco del cluster no cuenta como altura del mapa
                if (!esVistaBandaCiudadanaSeleccionada() && h > cat.alturaZonaDerecha && h > 150 && h < 600) {
                    cat.alturaZonaDerecha = h;   // se recuerda la altura del LOG
                    ConfigApp.put("ALTURA_ZONA_DERECHA", String.valueOf(h));
                    SwingUtilities.invokeLater(() -> { revalidate(); repaint(); });
                }
                h = Math.min(getHeight(), Math.max(h, Math.max(zonaDerecha.getPreferredSize().height, cat.alturaZonaDerecha)));
                zonaDerecha.setBounds(0, 0, getWidth(), Math.max(60, h));
                // Cluster debajo del mapa: con la cascada abierta (en el hueco que queda) o si se ha desplegado
                if (cluster.panelClusterMapa != null) {
                    int y = Math.max(60, h) + 6, alto = getHeight() - y;
                    boolean ver = (cluster.cascadaAbierta() || cluster.clusterDesplegado) && alto >= 60;
                    cluster.panelClusterMapa.setVisible(ver);
                    if (ver) cluster.panelClusterMapa.setBounds(0, y, getWidth(), alto);
                }
            }
        };
        cluster.envolturaDerechaRef = envolturaDerecha;
        cluster.panelClusterMapa = cluster.new PanelCluster(true);
        envolturaDerecha.add(cluster.panelClusterMapa);
        envolturaDerecha.setOpaque(false);
        envolturaDerecha.add(zonaDerecha);
        panelPrincipal.add(envolturaDerecha, BorderLayout.EAST);
        SwingUtilities.invokeLater(() -> actualizarMapa("", "", ""));
        // Al arrancar ya se ven los indicativos del radioclub (sin tener que cambiar de LOG)
        SwingUtilities.invokeLater(radioclub::actualizarControlesRadioclub);
        return panelPrincipal;
    }

    /** Varios campos juntos en una misma celda del formulario. */
    private JPanel grupoFormulario(JComponent... campos) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(0, -8, 0, 0));
        for (JComponent c : campos) p.add(c);
        return p;
    }

    private JPanel crearElementoFormulario(String etiqueta, JComponent campo) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(etiqueta);
        lbl.setFont(new Font("Arial", Font.PLAIN, 11));
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        campo.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(lbl);
        panel.add(Box.createVerticalStrut(2));
        panel.add(campo);
        return panel;
    }

    /**
     * Frecuencia (en MHz, como se guarda en la BD) que comparten todos los contactos de una actividad.
     * Devuelve "" si no hay contactos o si no todos tienen la misma frecuencia.
     */
    private String obtenerFrecuenciaComunDeActividad(int numeralActividad) {
        if (numeralActividad <= 0) return "";
        String sql = "SELECT DISTINCT freq_tx FROM qsos WHERE actividad_numeral = ? LIMIT 2";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, numeralActividad);
            try (ResultSet rs = pstmt.executeQuery()) {
                String unica = "";
                int distintas = 0;
                while (rs.next()) {
                    String f = rs.getString(1);
                    if (f == null || f.trim().isEmpty()) return "";
                    unica = f.trim();
                    distintas++;
                }
                if (distintas == 1) return unica;
            }
        } catch (Exception e) {
            System.err.println("Error al obtener la frecuencia de la actividad: " + e.getMessage());
        }
        return "";
    }

    private String formatearHzDesdeMHz(String valorMHz) {
        if (valorMHz == null || valorMHz.trim().isEmpty()) return "";
        try {
            double mhz = Double.parseDouble(valorMHz.trim().replace(",", "."));
            double hz = mhz * 1_000_000.0;
            return String.format(java.util.Locale.GERMANY, "%,.0f", hz);
        } catch (NumberFormatException e) {
            return valorMHz;
        }
    }

    private String convertirFreqFormularioAMHz(String textoCampo) {
        if (textoCampo == null) return "";
        String limpio = textoCampo.trim();
        if (limpio.isEmpty()) return "";

        try {
            String sinPuntos = limpio.replace(".", "").replace(",", ".");
            double valor = Double.parseDouble(sinPuntos);
            if (valor > 100000) {
                return String.format(java.util.Locale.US, "%.6f", valor / 1_000_000.0)
                        .replaceAll("0+$", "")
                        .replaceAll("\\.$", "");
            } else {
                return String.format(java.util.Locale.US, "%.6f", valor)
                        .replaceAll("0+$", "")
                        .replaceAll("\\.$", "");
            }
        } catch (NumberFormatException ex) {
            return limpio;
        }
    }

    // ===================== WSJT-X (FT8, FT4...): sus contactos entran solos en el log =====================
    private volatile java.net.DatagramSocket socketWsjtx;
    private final Set<String> adifWsjtxRecibidos = java.util.Collections.synchronizedSet(new HashSet<>());

    /**
     * Escucha lo que WSJT-X envía por la red local (UDP, puerto 2237 por defecto). Cuando en WSJT-X se registra
     * un contacto, llega en formato ADIF y se guarda como cualquier otro: operador, actividad, radioclub y subida online.
     * Solo se escucha: no se le manda nada a WSJT-X.
     */
    private void arrancarEscuchaWsjtx() {
        if ("0".equals(ConfigApp.get("WSJTX_ACTIVO", "1"))) return;
        int puerto = ConfigApp.getInt("WSJTX_PUERTO", 2237);
        Thread t = new Thread(() -> {
            try (java.net.DatagramSocket s = new java.net.DatagramSocket(null)) {
                s.setReuseAddress(true);
                s.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), puerto));
                socketWsjtx = s;
                byte[] buf = new byte[65536];
                while (!s.isClosed()) {
                    java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                    s.receive(p);
                    try {
                        String adif = adifDeMensajeWsjtx(java.util.Arrays.copyOf(p.getData(), p.getLength()));
                        if (adif != null && adifWsjtxRecibidos.add(adif)) SwingUtilities.invokeLater(() -> registrarQsoDeWsjtx(adif));
                    } catch (Exception ignored) { }   // mensajes que no son de un contacto registrado
                }
            } catch (Exception ex) {
                System.err.println("WSJT-X: no se puede escuchar en el puerto " + puerto + ": " + ex.getMessage());
            }
        }, "EscuchaWSJTX");
        t.setDaemon(true);
        t.start();
    }

    /** Mensaje de WSJT-X: si es "contacto registrado" (tipo 12, LoggedADIF) devuelve el ADIF; si no, null. */
    static String adifDeMensajeWsjtx(byte[] d) throws Exception {
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(d));
        if (in.readInt() != 0xADBCCBDA) return null;   // no es de WSJT-X
        in.readInt();                                  // versión del esquema
        int tipo = in.readInt();
        if (tipo != 12) return null;
        leerUtf8Qt(in);                                // id de la instancia de WSJT-X
        return leerUtf8Qt(in);
    }

    private static String leerUtf8Qt(java.io.DataInputStream in) throws Exception {
        int n = in.readInt();
        if (n == -1 || n == 0) return "";
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Guarda en el log el contacto que acaba de registrar WSJT-X. */
    private void registrarQsoDeWsjtx(String adif) { registrarQsoDigital(adif, "WSJT-X"); }

    /** Guarda en el log un contacto que llega en ADIF (de WSJT-X o de la ventana DIGITALES). Devuelve true si se ha guardado. */
    boolean registrarQsoDigital(String adif, String origen) {
        String reg = adif.toUpperCase().contains("<EOH>") ? adif.substring(adif.toUpperCase().indexOf("<EOH>") + 5) : adif;
        String call = extraerCampoAdi(reg, "CALL");
        if (call == null || call.isBlank()) return false;
        call = call.trim().toUpperCase();
        if (operadorActual == null || operadorActual.isBlank()) {
            lblEstado.setText(origen + ": no se ha guardado " + call + " porque no hay operador configurado");
            return false;
        }
        String modo = extraerCampoAdi(reg, "MODE"), sub = extraerCampoAdi(reg, "SUBMODE");
        if (modo == null) modo = "FT8";
        if (modo.equalsIgnoreCase("MFSK") && sub != null && !sub.isBlank()) modo = sub;   // FT4 llega como MFSK / FT4
        modo = normalizarOpcion(modo, MODOS);
        String f = extraerCampoAdi(reg, "QSO_DATE"), h = extraerCampoAdi(reg, "TIME_ON");
        String fecha = f != null && f.length() == 8 ? f.substring(6, 8) + "/" + f.substring(4, 6) + "/" + f.substring(0, 4)
                : LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String hora = h != null && h.length() >= 4 ? h.substring(0, 2) + ":" + h.substring(2, 4)
                : LocalTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("HH:mm"));
        String freq = "";
        String fr = extraerCampoAdi(reg, "FREQ");
        if (fr != null && !fr.isBlank()) {
            try { freq = String.format(java.util.Locale.US, "%.6f", Double.parseDouble(fr.trim())).replaceAll("0+$", "").replaceAll("\\.$", ""); }
            catch (Exception ignored) { }
        }
        String bandaAdi = extraerCampoAdi(reg, "BAND");
        String banda = bandaAdi == null || bandaAdi.isBlank() ? "" : normalizarOpcion(bandaAdi.toLowerCase(), BANDAS);
        if (banda.isEmpty() && !freq.isEmpty()) {
            try { banda = bandaPorFrecuencia(Double.parseDouble(freq) * 1e6); } catch (Exception ignored) { }
        }
        String rstS = valorAdi(reg, "RST_SENT"), rstR = valorAdi(reg, "RST_RCVD");
        String nombre = valorAdi(reg, "NAME").toUpperCase(), qth = valorAdi(reg, "QTH").toUpperCase(), grid = valorAdi(reg, "GRIDSQUARE").toUpperCase();

        // ¿Ya está en el log? (por si WSJT-X lo manda dos veces)
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM qsos WHERE operador = ? AND indicativo = ? AND fecha = ? AND hora = ? AND banda = ? AND modo = ? LIMIT 1")) {
            ps.setString(1, operadorActual); ps.setString(2, call); ps.setString(3, fecha); ps.setString(4, hora); ps.setString(5, banda); ps.setString(6, modo);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return false; }
        } catch (Exception ignored) { }

        // Actividad elegida: entra en ella si cumple sus condiciones; si no, va solo al log
        String actividadInterna = "", aviso = "";
        int numeral = obtenerNumeralDeActividadDesdeSeleccion();
        if (numeral > 0) {
            boolean vale;
            if ("PUNTOS".equals(obtenerTipoActividadPorNumeral(numeral))) {
                vale = esBandaConfigurada(numeral, banda) && esModoConfigurado(numeral, modo);
            } else {
                String bf = obtenerBandaSimpleDeActividad(numeral), mf = obtenerModoSimpleDeActividad(numeral);
                vale = (bf.isEmpty() || banda.equalsIgnoreCase(bf)) && (mf.isEmpty() || modo.equalsIgnoreCase(mf));
            }
            if (vale && buscarDuplicadoEnActividad(numeral, fecha, operadorActual, call, banda, modo, nombre, qth) != null) {
                vale = false;
                aviso = " (ya estaba en la actividad: solo va al log)";
            } else if (!vale) {
                aviso = " (la actividad no admite " + banda + " " + modo + ": solo va al log)";
            }
            if (vale) actividadInterna = obtenerNombreInternoDeActividad(numeral);
        }
        guardarFilaQSO(fecha, hora, call, banda, modo, freq, freq, rstS, rstR, nombre, qth, grid, actividadInterna, false);
        cargarQSOsDesdeBaseDatos();
        lblEstado.setText(origen + ": contacto con " + call + " (" + banda + " " + modo + ") guardado" + aviso);
        completarContactoGuardado(call, fecha, hora, banda, modo);
        return true;
    }

    /**
     * Nombre, QTH y locator de un indicativo, como al escribirlo en la ventana principal: datos propios, Bitácora 26 y,
     * fuera de CB, QRZ.com / HamQTH. Devuelve {nombre, qth, grid} (con "" en lo que no se encuentre). Fuera del hilo de la ventana.
     */
    String[] datosCompletosIndicativo(String call, boolean esCB) {
        String[] completo = {"", "", ""};
        try (Connection conn = DatabaseManager.conectar()) {
            completo = mezclarDatos(completo, buscarDatosPropios(conn, call, esCB, -1, "", ""));
        } catch (Exception ignored) { }
        try {
            if (faltaAlgo(completo) && cuentaB26 != null && !CuentaBitacora.cuentasB26().isEmpty()) {
                List<String[]> fichas = cuentaB26.buscarFichasB26(call, esCB);
                if (fichas != null && fichas.size() == 1) completo = mezclarDatos(completo, fichas.getFirst());
            }
            if (!esCB && (faltaAlgo(completo) || completo[2].length() <= 4)) {
                String[] ext = !ConfigApp.get("QRZ_USER", "").isEmpty() && !ConfigApp.get("QRZ_PWD", "").isEmpty() ? buscarEnQrzSincrono(call) : null;
                if (ext == null && hayCuentaHamQth()) ext = buscarEnHamQth(call);
                if (ext != null && ext[2] != null && ext[2].trim().length() > completo[2].length()
                        && (completo[2].isEmpty() || ext[2].trim().toUpperCase().startsWith(completo[2].toUpperCase()))) completo[2] = ext[2].trim();
                completo = mezclarDatos(completo, ext);
            }
        } catch (Exception ignored) { }
        return completo;
    }

    /**
     * Después de guardar un contacto de los modos digitales: se completan nombre, QTH y locator (de 6) como al escribir
     * el indicativo en la ventana principal (datos propios, Bitácora 26 y, fuera de CB, QRZ.com / HamQTH). En segundo plano.
     */
    private void completarContactoGuardado(String call, String fecha, String hora, String banda, String modo) {
        final String op = operadorActual;
        Thread t = new Thread(() -> {
            try (Connection conn = DatabaseManager.conectar()) {
                int id = -1;
                String nom = "", qth = "", grid = "";
                try (PreparedStatement ps = conn.prepareStatement("SELECT id, COALESCE(nombre,''), COALESCE(qth,''), COALESCE(grid,'') FROM qsos "
                        + "WHERE operador = ? AND indicativo = ? AND fecha = ? AND hora = ? AND banda = ? AND modo = ? ORDER BY id DESC LIMIT 1")) {
                    ps.setString(1, op); ps.setString(2, call); ps.setString(3, fecha); ps.setString(4, hora); ps.setString(5, banda); ps.setString(6, modo);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { id = rs.getInt(1); nom = rs.getString(2); qth = rs.getString(3); grid = rs.getString(4); } }
                }
                if (id < 0) return;
                boolean esCB = "CB".equalsIgnoreCase(banda.trim());
                String[] completo = mezclarDatos(new String[]{nom, qth, grid.length() > 4 ? grid : ""}, buscarDatosPropios(conn, call, esCB, id, nom, qth));
                if (faltaAlgo(completo) && cuentaB26 != null && !CuentaBitacora.cuentasB26().isEmpty()) {
                    List<String[]> fichas = cuentaB26.buscarFichasB26(call, esCB);
                    if (fichas != null && fichas.size() == 1) completo = mezclarDatos(completo, fichas.getFirst());
                }
                if (!esCB && faltaAlgo(completo)) {
                    String[] ext = !ConfigApp.get("QRZ_USER", "").isEmpty() && !ConfigApp.get("QRZ_PWD", "").isEmpty() ? buscarEnQrzSincrono(call) : null;
                    if (ext == null && hayCuentaHamQth()) ext = buscarEnHamQth(call);
                    completo = mezclarDatos(completo, ext);
                }
                // el locator de 4 del mensaje se queda si no hay uno más completo que empiece igual
                if (completo[2].isEmpty() || (!grid.isEmpty() && !completo[2].toUpperCase().startsWith(grid.toUpperCase().substring(0, Math.min(4, grid.length())))))
                    completo[2] = grid;
                if (completo[0].equals(nom) && completo[1].equals(qth) && completo[2].equalsIgnoreCase(grid)) return;
                try (PreparedStatement up = conn.prepareStatement("UPDATE qsos SET nombre = ?, qth = ?, grid = ? WHERE id = ?")) {
                    up.setString(1, completo[0].toUpperCase()); up.setString(2, completo[1].toUpperCase()); up.setString(3, completo[2].toUpperCase()); up.setInt(4, id);
                    up.executeUpdate();
                }
                SwingUtilities.invokeLater(this::cargarQSOsDesdeBaseDatos);
            } catch (Exception ignored) { }
        }, "CompletarContactoDigital");
        t.setDaemon(true);
        t.start();
    }

    private String valorAdi(String reg, String etiqueta) {
        String v = extraerCampoAdi(reg, etiqueta);
        return v == null ? "" : v.trim();
    }

    void guardarQSO() {
        String indicativo = txtIndicativo.getText().trim();
        if (indicativo.isEmpty()) {
            JOptionPane.showMessageDialog(this, "El indicativo (Callsign) es obligatorio.", "Atención", JOptionPane.WARNING_MESSAGE);
            txtIndicativo.requestFocus();
            return;
        }

        if (panelNombreContenedor != null && cbNombres != null
                && panelNombreContenedor.getComponent(1).isVisible()) {
            Object sel = cbNombres.getSelectedItem();
            if (sel == null || OPCION_SELECCIONAR.equals(sel.toString())) {
                JOptionPane.showMessageDialog(this,
                        "Hay varios operadores con el indicativo " + indicativo + ".\n" +
                                "Selecciona uno en el desplegable Nombre antes de guardar.",
                        "Atención", JOptionPane.WARNING_MESSAGE);
                cbNombres.requestFocus();
                return;
            }
        }

        if (operadorActual == null || operadorActual.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No has configurado tu indicativo de operador.\n" +
                            "Configúralo antes de guardar QSOs.",
                    "Operador no configurado", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // RADIOCLUB: comprobaciones antes de guardar
        int numeralParaRC = obtenerNumeralDeActividadDesdeSeleccion();
        if (!modoRadioclub && (radioclub.esVistaRadioclubSeleccionada() || radioclub.esActividadDelRadioclub(numeralParaRC))) {
            JOptionPane.showMessageDialog(this,
                    (radioclub.esVistaRadioclubSeleccionada()
                            ? "Estás en el " + NOMBRE_VISTA_RC + "."
                            : "Esta actividad es del " + radioclub.nombreRadioclub() + ".") + "\n\n" +
                            "Configura los indicativos del radioclub en Mi Estación → Radioclub.",
                    "Modo radioclub desactivado", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (modoRadioclub && radioclub.indicativoRadioclubElegido().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Estás en modo " + radioclub.nombreRadioclub() + " pero no hay ningún indicativo elegido.\n" +
                            "Configura los indicativos del radioclub en Mi Estación → Radioclub.",
                    "Falta el indicativo del radioclub", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String[] fechaHoraUtc = horaLocal()
                ? convertirFechaHora(txtFecha.getText().trim(), txtHora.getText().trim(), false)
                : new String[]{txtFecha.getText().trim(), txtHora.getText().trim()};
        String fecha = fechaHoraUtc[0];
        String hora = fechaHoraUtc[1];
        String banda = (String) cbBanda.getSelectedItem();
        String modo = (String) cbModo.getSelectedItem();

        if (banda == null || banda.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Debes seleccionar una banda antes de guardar el contacto.",
                    "Banda obligatoria", JOptionPane.WARNING_MESSAGE);
            cbBanda.requestFocus();
            return;
        }

        if (modo == null || modo.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Debes seleccionar un modo antes de guardar el contacto.",
                    "Modo obligatorio", JOptionPane.WARNING_MESSAGE);
            cbModo.requestFocus();
            return;
        }

        String freqTx = convertirFreqFormularioAMHz(txtFreqTx.getText().trim());
        String freqRx = convertirFreqFormularioAMHz(txtFreqRx.getText().trim());
        String rstSent = txtRstSent.getText().trim();
        String rstRcvd = txtRstRcvd.getText().trim();
        String nombre = txtNombre.getText().trim();
        String qth = txtQth.getText().trim();
        String grid = txtGrid.getText().trim();
        comentarioParaGuardar = txtComentario == null ? "" : txtComentario.getText().trim();

        int actividadNumeralSel = obtenerNumeralDeActividadDesdeSeleccion();
        String actividadInterna = "";
        if (actividadNumeralSel > 0) {
            actividadInterna = obtenerNombreInternoDeActividad(actividadNumeralSel);
        }

        String tipoActividad = "";
        if (actividadNumeralSel > 0) {
            tipoActividad = obtenerTipoActividadPorNumeral(actividadNumeralSel);
        }

        boolean esActividadPuntos = "PUNTOS".equals(tipoActividad);

        if (actividadNumeralSel > 0) {
            String duplicadoHora = buscarDuplicadoEnActividad(
                    actividadNumeralSel, fecha, operadorActual, indicativo, banda, modo, nombre, qth);
            if (duplicadoHora != null) {
                JOptionPane.showMessageDialog(this,
                        "Ya has registrado hoy un QSO con " + indicativo +
                                " en " + banda + " " + modo + ".\n\n" +
                                "• Hora del QSO original: " + duplicadoHora + "\n" +
                                "• Fecha: " + fecha + "\n" +
                                "• No se contabilizará de nuevo en esta actividad.",
                        "Contacto duplicado en la actividad",
                        JOptionPane.WARNING_MESSAGE);

                limpiarFormularioTrasGuardar();
                return;
            }
        }

        if (esActividadPuntos) {
            boolean bandaOk = esBandaConfigurada(actividadNumeralSel, banda);
            boolean modoOk = esModoConfigurado(actividadNumeralSel, modo);

            if (!bandaOk || !modoOk) {
                StringBuilder falta = new StringBuilder();
                if (!bandaOk)
                    falta.append("• La banda ").append(banda).append(" no está configurada en la actividad.\n");
                if (!modoOk) falta.append("• El modo ").append(modo).append(" no está configurado en la actividad.\n");

                JOptionPane.showMessageDialog(this,
                        "El QSO no se guardará en la actividad seleccionada por:\n\n" +
                                falta +
                                "\nSe guardará en tu LOG Principal sin actividad.",
                        "Configuración incompleta en la actividad",
                        JOptionPane.WARNING_MESSAGE);

                guardarFilaQSO(fecha, hora, indicativo, banda, modo, freqTx, freqRx,
                        rstSent, rstRcvd, nombre, qth, grid, "", false);

                cargarQSOsDesdeBaseDatos();
                limpiarFormularioTrasGuardar();
                return;
            }
        } else if (actividadNumeralSel > 0) {
            String bandaFija = obtenerBandaSimpleDeActividad(actividadNumeralSel);
            String modoFijo = obtenerModoSimpleDeActividad(actividadNumeralSel);

            boolean bandaOk = bandaFija.isEmpty() || banda.equalsIgnoreCase(bandaFija);
            boolean modoOk = modoFijo.isEmpty() || modo.equalsIgnoreCase(modoFijo);

            if (!bandaOk || !modoOk) {
                StringBuilder motivo = new StringBuilder();
                if (!bandaOk) motivo.append("• La banda de esta actividad es ").append(bandaFija)
                        .append(" y has puesto ").append(banda).append(".\n");
                if (!modoOk) motivo.append("• El modo de esta actividad es ").append(modoFijo)
                        .append(" y has puesto ").append(modo).append(".\n");

                JOptionPane.showMessageDialog(this,
                        "Esta actividad de Contacto Único solo admite:\n\n" +
                                "• Banda: " + (bandaFija.isEmpty() ? "(libre)" : bandaFija) + "\n" +
                                "• Modo:  " + (modoFijo.isEmpty() ? "(libre)" : modoFijo) + "\n\n" +
                                motivo +
                                "\nCorrige el formulario o cambia de actividad.",
                        "Banda o modo no permitidos",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        guardarFilaQSO(fecha, hora, indicativo, banda, modo, freqTx, freqRx,
                rstSent, rstRcvd, nombre, qth, grid, actividadInterna, false);

        cargarQSOsDesdeBaseDatos();
        limpiarFormularioTrasGuardar();
    }

    private boolean esModoConfigurado(int numeralActividad, String modo) {
        if (modo == null || modo.trim().isEmpty() || numeralActividad <= 0) return false;
        String nombreInterno = obtenerNombreInternoDeActividad(numeralActividad);
        if (nombreInterno.isEmpty()) return false;
        String sql = "SELECT 1 FROM actividad_puntos WHERE actividad = ? AND tipo = 'MODO' AND UPPER(valor) = UPPER(?) LIMIT 1";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, nombreInterno);
            pstmt.setString(2, modo.trim());
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private String buscarDuplicadoEnActividad(int numeralActividad, String fecha, String operador,
                                              String indicativo, String banda, String modo,
                                              String nombre, String qth) {
        // En CB dos personas pueden usar el mismo indicativo: solo es repetido si además coinciden QRA y QTH
        boolean cb = "CB".equalsIgnoreCase(banda);
        String sql = "SELECT hora FROM qsos " +
                "WHERE actividad_numeral = ? AND fecha = ? AND operador = ? " +
                "AND indicativo = ? AND banda = ? AND modo = ?" +
                (cb ? " AND UPPER(TRIM(COALESCE(nombre, ''))) = ? AND UPPER(TRIM(COALESCE(qth, ''))) = ?" : "") +
                " LIMIT 1";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, numeralActividad);
            pstmt.setString(2, fecha);
            pstmt.setString(3, operador);
            pstmt.setString(4, indicativo);
            pstmt.setString(5, banda);
            pstmt.setString(6, modo);
            if (cb) {
                pstmt.setString(7, nombre == null ? "" : nombre.trim().toUpperCase());
                pstmt.setString(8, qth == null ? "" : qth.trim().toUpperCase());
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("hora");
                }
            }
        } catch (Exception e) {
            System.err.println("Error al buscar duplicado: " + e.getMessage());
        }
        return null;
    }

    /** Comentario escrito en el formulario: lo usa el siguiente guardarFilaQSO (y se vacía). */
    private String comentarioParaGuardar = "";

    private void guardarFilaQSO(String fecha, String hora, String indicativo, String banda, String modo,
                                String freqTx, String freqRx, String rstSent, String rstRcvd,
                                String nombre, String qth, String grid,
                                String actividad, boolean marcarSubido) {
        String uuid = java.util.UUID.randomUUID().toString();
        String comentario = comentarioParaGuardar == null ? "" : comentarioParaGuardar;
        comentarioParaGuardar = "";

        int actividadNumeral = 0;
        String actividadNombreInterno = "";
        if (actividad != null && !actividad.isEmpty()) {
            actividadNombreInterno = actividad;
            actividadNumeral = obtenerNumeralDeActividadPorNombre(actividad);
        }

        int puntosQso = 0;
        boolean esPuntos = false;
        if (actividadNumeral > 0) {
            String tipoAct = obtenerTipoActividadPorNumeral(actividadNumeral);
            esPuntos = "PUNTOS".equals(tipoAct);
        }

        if (esPuntos) {
            Map<String, Integer> puntosBandas = cargarPuntosPorTipo(actividadNombreInterno, "BANDA");
            Map<String, Integer> puntosModos = cargarPuntosPorTipo(actividadNombreInterno, "MODO");
            Map<String, Integer> puntosEspeciales = cargarPuntosPorTipo(actividadNombreInterno, "ESPECIAL");

            if (banda != null) {
                Integer p = puntosBandas.get(banda.trim().toUpperCase());
                if (p != null) puntosQso += p;
            }
            if (modo != null) {
                Integer p = puntosModos.get(modo.trim().toUpperCase());
                if (p != null) puntosQso += p;
            }
            if (operadorActual != null && !operadorActual.trim().isEmpty()) {
                Integer p = puntosEspeciales.get(operadorActual.trim().toUpperCase());
                if (p != null) puntosQso += p;
            }
            // RADIOCLUB: en modo radioclub también puntúa el indicativo del radioclub usado
            String indRC = radioclub.indicativoRadioclubElegido();
            if (!indRC.isEmpty() && !indRC.equalsIgnoreCase(operadorActual == null ? "" : operadorActual.trim())) {
                Integer p = puntosEspeciales.get(indRC);
                if (p != null) puntosQso += p;
            }
        }

        try (Connection conn = DatabaseManager.conectar()) {
            if (esPuntos) {
                int idExistente = -1;
                int puntosActuales = 0;
                int contactosActuales = 0;

                // En CB, mismo indicativo con distinto QRA o QTH es otra persona: se suma en su propia fila
                boolean cbPersona = "CB".equalsIgnoreCase(banda);
                String sqlSel = "SELECT id, puntos, num_contactos FROM qsos " +
                        "WHERE actividad_numeral = ? AND indicativo = ? AND banda = ? AND modo = ?" +
                        (cbPersona ? " AND UPPER(TRIM(COALESCE(nombre, ''))) = ? AND UPPER(TRIM(COALESCE(qth, ''))) = ?" : "") +
                        " LIMIT 1";
                try (PreparedStatement ps = conn.prepareStatement(sqlSel)) {
                    ps.setInt(1, actividadNumeral);
                    ps.setString(2, indicativo);
                    ps.setString(3, banda);
                    ps.setString(4, modo);
                    if (cbPersona) {
                        ps.setString(5, nombre == null ? "" : nombre.trim().toUpperCase());
                        ps.setString(6, qth == null ? "" : qth.trim().toUpperCase());
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            idExistente = rs.getInt("id");
                            puntosActuales = rs.getInt("puntos");
                            contactosActuales = rs.getInt("num_contactos");
                        }
                    }
                }

                if (idExistente > 0) {
                    String sqlUpd = "UPDATE qsos SET puntos = ?, num_contactos = ? WHERE id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sqlUpd)) {
                        ps.setInt(1, puntosActuales + puntosQso);
                        ps.setInt(2, contactosActuales + 1);
                        ps.setInt(3, idExistente);
                        ps.executeUpdate();
                    }
                    if (modoRadioclub) {
                        // RADIOCLUB: si se suma en modo radioclub, el contacto pasa a verse también en su log
                        try (PreparedStatement ps = conn.prepareStatement(
                                "UPDATE qsos SET radioclub = 1, estacion = CASE WHEN COALESCE(estacion, '') = '' THEN ? ELSE estacion END WHERE id = ?")) {
                            ps.setString(1, radioclub.indicativoRadioclubElegido());
                            ps.setInt(2, idExistente);
                            ps.executeUpdate();
                        }
                    }
                    return;
                }
            }

            // RADIOCLUB: en modo radioclub el contacto va también al LOG Radioclub, con el indicativo elegido
            boolean delRadioclub = modoRadioclub;
            String estacion = modoRadioclub ? radioclub.indicativoRadioclubElegido() : "";

            String sql = "INSERT INTO qsos (uuid, operador, fecha, hora, indicativo, banda, modo, " +
                    "freq_tx, freq_rx, rst_sent, rst_rcvd, nombre, qth, grid, actividad, subido, " +
                    "actividad_numeral, puntos, num_contactos, radioclub, estacion, comentario) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, uuid);
                pstmt.setString(2, operadorActual);
                pstmt.setString(3, fecha);
                pstmt.setString(4, hora);
                pstmt.setString(5, indicativo);
                pstmt.setString(6, banda);
                pstmt.setString(7, modo);
                pstmt.setString(8, freqTx);
                pstmt.setString(9, freqRx);
                pstmt.setString(10, rstSent);
                pstmt.setString(11, rstRcvd);
                pstmt.setString(12, nombre);
                pstmt.setString(13, qth);
                pstmt.setString(14, grid);
                pstmt.setString(15, actividadNombreInterno);
                pstmt.setInt(16, marcarSubido ? 1 : 0);
                pstmt.setInt(17, actividadNumeral);
                pstmt.setInt(18, puntosQso);
                pstmt.setInt(19, 1);
                pstmt.setInt(20, delRadioclub ? 1 : 0);
                pstmt.setString(21, estacion);
                pstmt.setString(22, comentario);
                pstmt.executeUpdate();
            }
            subirQsoAutomatico(uuid);   // a los servicios online que tengan marcada la casilla
            cuentaB26.contactoGuardado();   // Bitácora 26: se sube enseguida y, si está marcado, se manda mi QSL
            // Diploma automático: si el diploma de la actividad está configurado así, se crea al añadir el contacto
            if (actividadNumeral > 0 && "AUTO".equals(ConfigApp.get("DIPLOMA_MODO_" + actividadNumeral, ""))) {
                Map<String, String> qsoDip = new HashMap<>();
                qsoDip.put("CALL", indicativo == null ? "" : indicativo.trim().toUpperCase());
                String[] f = fecha == null ? new String[0] : fecha.split("/");
                if (f.length == 3) qsoDip.put("QSO_DATE", f[2] + f[1] + f[0]);
                if (hora != null) qsoDip.put("TIME_ON", hora.replace(":", ""));
                qsoDip.put("BAND", banda == null ? "" : banda);
                if (freqTx != null && !freqTx.isBlank()) qsoDip.put("FREQ", freqTx.trim());
                qsoDip.put("MODE", modo == null ? "" : modo);
                qsoDip.put("RST_SENT", rstSent == null ? "" : rstSent);
                qsoDip.put("RST_RCVD", rstRcvd == null ? "" : rstRcvd);
                qsoDip.put("QTH", qth == null ? "" : qth);
                qsoDip.put("NAME", nombre == null ? "" : nombre);
                GeneradorDiplomaUI.generarAutomatico(actividadNumeral, nombreVisibleDeActividad(actividadNombreInterno), qsoDip,
                        msg -> SwingUtilities.invokeLater(() -> { if (lblEstado != null) lblEstado.setText(msg); }));
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Error al guardar en la base de datos: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    int obtenerNumeralDeActividadDesdeSeleccion() {
        Object sel = cbSelectorActividad.getSelectedItem();
        if (sel == null) return 0;
        String txt = sel.toString();
        int pos = txt.indexOf(" - ");
        if (pos <= 0) return 0;
        try {
            return Integer.parseInt(txt.substring(0, pos).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int obtenerNumeralDeActividadPorNombre(String nombreInterno) {
        if (nombreInterno == null) return 0;
        int idx = nombreInterno.lastIndexOf(" #");
        if (idx <= 0) return 0;
        try {
            return Integer.parseInt(nombreInterno.substring(idx + 2).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String obtenerNombreInternoDeActividad(int numeralActividad) {
        if (numeralActividad <= 0) return "";
        String sql = "SELECT nombre FROM actividades WHERE numeral = ? LIMIT 1";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, numeralActividad);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getString("nombre");
            }
        } catch (Exception e) {
            System.err.println("Error al obtener nombre interno de actividad: " + e.getMessage());
        }
        return "";
    }

    private String obtenerTipoActividadPorNumeral(int numeralActividad) {
        if (numeralActividad <= 0) return "";
        String sql = "SELECT tipo FROM actividades WHERE numeral = ? LIMIT 1";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, numeralActividad);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getString("tipo");
            }
        } catch (Exception e) {
            System.err.println("Error al obtener tipo de actividad por numeral: " + e.getMessage());
        }
        return "";
    }

    private String obtenerBandaSimpleDeActividad(int numeralActividad) {
        if (numeralActividad <= 0) return "";
        String sql = "SELECT banda_simple FROM actividades WHERE numeral = ? LIMIT 1";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, numeralActividad);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString("banda_simple");
                    return v == null ? "" : v.trim().toUpperCase();
                }
            }
        } catch (Exception e) {
            System.err.println("Error al obtener banda simple: " + e.getMessage());
        }
        return "";
    }

    private String obtenerModoSimpleDeActividad(int numeralActividad) {
        if (numeralActividad <= 0) return "";
        String sql = "SELECT modo_simple FROM actividades WHERE numeral = ? LIMIT 1";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, numeralActividad);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString("modo_simple");
                    return v == null ? "" : v.trim().toUpperCase();
                }
            }
        } catch (Exception e) {
            System.err.println("Error al obtener modo simple: " + e.getMessage());
        }
        return "";
    }

    String nombreVisibleDeActividad(String nombreInterno) {
        if (nombreInterno == null) return "";
        int idx = nombreInterno.lastIndexOf(" #");
        return idx > 0 ? nombreInterno.substring(0, idx) : nombreInterno;
    }

    /** Borra CALLSIGN, Nombre, QTH y Grid (lo demás se queda como está). */
    private void limpiarContactoAlCambiarActividad() {
        if (txtIndicativo == null) return;
        txtIndicativo.setText("");
        if (txtComentario != null) txtComentario.setText("");
        txtNombre.setText("");
        txtQth.setText("");
        txtGrid.setText("");
        if (lblQrzIcon != null) lblQrzIcon.setVisible(false);
        alternarModoNombre(false);
        if (cbNombres != null) {
            cbNombres.removeAllItems();
            opcionesNombres.clear();
        }
    }

    private void limpiarFormularioTrasGuardar() {
        txtIndicativo.setText("");
        if (txtComentario != null) txtComentario.setText("");
        txtNombre.setText("");
        txtQth.setText("");
        txtGrid.setText("");
        txtRstSent.setText("59");
        txtRstRcvd.setText("59");
        if (lblQrzIcon != null) lblQrzIcon.setVisible(false);

        alternarModoNombre(false);
        if (cbNombres != null) {
            cbNombres.removeAllItems();
            opcionesNombres.clear();
        }

        String[] ahora = ahoraSegunModo();
        txtFecha.setText(ahora[0]);
        txtHora.setText(ahora[1]);
        horaEscritaAMano = false;
        txtIndicativo.requestFocus();
    }

    /** Si el contacto editado es de CB, copia el nombre/QTH corregido a la lista BANDA CIUDADANA. */
    private void propagarEdicionADirectorioCB(String exprIndicativo, String campo, String valor) {
        if (!campo.equals("nombre") && !campo.equals("qth")) return;
        String v = valor == null ? "" : valor.trim().toUpperCase();
        String sql = "UPDATE cb_directorio SET " + campo + " = ? WHERE eliminado = 0 AND indicativo = " + exprIndicativo
                + " AND EXISTS (SELECT 1 FROM qsos WHERE indicativo = " + exprIndicativo + " AND UPPER(banda) = 'CB')";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, v);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("No se pudo actualizar la lista CB: " + e.getMessage());
        }
    }

    private void actualizarQSOEnBaseDatos(int id, String campo, String valor) {
        String sql = "UPDATE qsos SET " + campo + " = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, valor);
            pstmt.setInt(2, id);
            pstmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("Error al actualizar la base de datos: " + e.getMessage());
        }
    }

    private boolean esVistaBandaCiudadanaSeleccionada() {
        return cbSelectorActividad != null && NOMBRE_VISTA_CB.equals(cbSelectorActividad.getSelectedItem());
    }

    void cargarQSOsDesdeBaseDatos() {
        if (esVistaBandaCiudadanaSeleccionada()) {
            cargarVistaBandaCiudadana();
            return;
        }
        sincronizarDirectorioCBEnSegundoPlano();
        int numeral = obtenerNumeralDeActividadDesdeSeleccion();

        if (numeral <= 0) {
            cargarVistaEstandar(0, false);
            return;
        }

        String tipoActividad = obtenerTipoActividadPorNumeral(numeral);
        boolean esVistaPuntos = "PUNTOS".equals(tipoActividad);

        if (esVistaPuntos) {
            cargarVistaPuntos(numeral);
        } else {
            cargarVistaEstandar(numeral, true);
        }
    }

    /** Editor de celda: solo los caracteres permitidos (regex de un carácter) y/o en mayúsculas. */
    private javax.swing.table.TableCellEditor editorTabla(String permitidos, boolean mayusculas) {
        String clave = permitidos + "|" + mayusculas;
        return editoresTabla.computeIfAbsent(clave, k -> {
            JTextField campo = new JTextField();
            ((AbstractDocument) campo.getDocument()).setDocumentFilter(new FiltroTexto(permitidos, mayusculas));
            return new DefaultCellEditor(campo);
        });
    }

    private static class FiltroTexto extends DocumentFilter {
        private final String permitidos;
        private final boolean mayusculas;

        FiltroTexto(String permitidos, boolean mayusculas) {
            this.permitidos = permitidos;
            this.mayusculas = mayusculas;
        }

        private String filtrar(String t) {
            if (t == null) return "";
            if (mayusculas) t = t.toUpperCase();
            if (permitidos != null) t = t.replaceAll("[^" + permitidos.substring(1, permitidos.length() - 1) + "]", "");
            return t;
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String text, AttributeSet a) throws BadLocationException {
            super.insertString(fb, offset, filtrar(text), a);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet a) throws BadLocationException {
            super.replace(fb, offset, length, filtrar(text), a);
        }
    }

    boolean horaLocal() {
        return cbHoraModo != null && "LOCAL".equals(cbHoraModo.getSelectedItem());
    }

    private String[] ahoraSegunModo() {
        java.time.ZonedDateTime z = horaLocal()
                ? java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
                : java.time.ZonedDateTime.now(ZoneOffset.UTC);
        return new String[]{z.format(FMT_FECHA_UI), z.format(FMT_HORA_UI)};
    }

    /** Convierte fecha (dd/MM/yyyy) y hora (HH:mm) entre UTC y hora local. aLocal=true: UTC a local; false: local a UTC. */
    private String[] convertirFechaHora(String fecha, String hora, boolean aLocal) {
        try {
            java.time.LocalDateTime t = java.time.LocalDateTime.of(
                    LocalDate.parse(fecha.trim(), FMT_FECHA_UI), LocalTime.parse(hora.trim(), FMT_HORA_UI));
            java.time.ZonedDateTime z = aLocal
                    ? t.atZone(ZoneOffset.UTC).withZoneSameInstant(java.time.ZoneId.systemDefault())
                    : t.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC);
            return new String[]{z.format(FMT_FECHA_UI), z.format(FMT_HORA_UI)};
        } catch (Exception e) {
            return new String[]{fecha, hora};
        }
    }

    static String txtCB(Object valor) {
        return valor == null ? "" : valor.toString().trim().toUpperCase();
    }

    /** Añade a la lista de CB los indicativos de los contactos en banda CB que aún no estén (sin pisar lo editado). */
    void sincronizarDirectorioCB(Connection conn) throws Exception {
        sincronizarDirectorioCB(conn, false);
    }

    /**
     * Igual que la anterior, pero devuelve cuántos indicativos se han añadido a la lista.
     * Con reactivarBorrados=true también vuelven los que se quitaron a mano de la lista.
     */
    int sincronizarDirectorioCB(Connection conn, boolean reactivarBorrados) throws Exception {
        int anadidos = 0;
        Map<String, String[]> deQsos = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     // Solo los contactos de CB del LOG Radioclub y de las actividades del radioclub
                     "SELECT id, indicativo, nombre, qth FROM qsos WHERE UPPER(banda) = 'CB' AND (COALESCE(radioclub, 0) = 1 "
                             + "OR actividad_numeral IN (SELECT numeral FROM actividades WHERE COALESCE(radioclub, 0) = 1 AND numeral > 0)) ORDER BY id ASC")) {
            while (rs.next()) {
                String ind = txtCB(rs.getString(2));
                if (ind.isEmpty()) continue;
                String[] f = deQsos.computeIfAbsent(ind, k -> new String[]{"", "", "0"});
                String nombre = txtCB(rs.getString(3));
                String qth = txtCB(rs.getString(4));
                if (!nombre.isEmpty()) f[0] = nombre;
                if (!qth.isEmpty()) f[1] = qth;
                f[2] = String.valueOf(rs.getLong(1));
            }
        }

        Map<String, Object[]> dir = new java.util.HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT indicativo, nombre, qth, eliminado, corte FROM cb_directorio")) {
            while (rs.next()) {
                dir.put(txtCB(rs.getString(1)), new Object[]{txtCB(rs.getString(2)), txtCB(rs.getString(3)),
                        rs.getInt(4), rs.getLong(5)});
            }
        }

        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO cb_directorio (indicativo, nombre, qth, eliminado, corte) VALUES (?, ?, ?, 0, 0)");
             PreparedStatement upd = conn.prepareStatement(
                     "UPDATE cb_directorio SET nombre = ?, qth = ?, eliminado = 0 WHERE indicativo = ?")) {
            for (Map.Entry<String, String[]> e : deQsos.entrySet()) {
                String ind = e.getKey();
                String[] f = e.getValue();
                Object[] d = dir.get(ind);
                if (d == null) {
                    ins.setString(1, ind);
                    ins.setString(2, f[0]);
                    ins.setString(3, f[1]);
                    ins.executeUpdate();
                    anadidos++;
                } else if ((Integer) d[2] == 1) {
                    // Borrado de la lista: reaparece si hay contactos posteriores al borrado
                    // o si se pide expresamente con el botón Actualizar
                    if (reactivarBorrados || Long.parseLong(f[2]) > (Long) d[3]) {
                        upd.setString(1, f[0]);
                        upd.setString(2, f[1]);
                        upd.setString(3, ind);
                        upd.executeUpdate();
                        anadidos++;
                    }
                } else {
                    String n = (String) d[0];
                    String q = (String) d[1];
                    String nn = n.isEmpty() ? f[0] : n;
                    String qq = q.isEmpty() ? f[1] : q;
                    if (!nn.equals(n) || !qq.equals(q)) {
                        upd.setString(1, nn);
                        upd.setString(2, qq);
                        upd.setString(3, ind);
                        upd.executeUpdate();
                    }
                }
            }
            conn.commit();
        } catch (Exception ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
        return anadidos;
    }

    private static String indicativoDeQso(int id) {
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement("SELECT indicativo FROM qsos WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Se ha cambiado el indicativo de un contacto: si es de CB y del radioclub (LOG Radioclub o actividad del radioclub),
     * en la lista BANDA CIUDADANA el nuevo sustituye al antiguo (el antiguo se quita si ya no queda en el radioclub).
     */
    private void cambiarIndicativoEnListaCB(int idQso, String indicativoAntes) {
        try (Connection conn = DatabaseManager.conectar()) {
            String ind, nom, qth;
            try (PreparedStatement ps = conn.prepareStatement("SELECT indicativo, nombre, qth FROM qsos WHERE id = ? AND UPPER(banda) = 'CB' AND (COALESCE(radioclub, 0) = 1 "
                    + "OR actividad_numeral IN (SELECT numeral FROM actividades WHERE COALESCE(radioclub, 0) = 1 AND numeral > 0))")) {
                ps.setInt(1, idQso);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return;   // no es un contacto de CB del radioclub
                    ind = txtCB(rs.getString(1)); nom = txtCB(rs.getString(2)); qth = txtCB(rs.getString(3));
                }
            }
            if (ind.isEmpty() || ind.equals(txtCB(indicativoAntes))) return;
            // Si el antiguo tenía nombre o QTH en la lista y el contacto no, se conservan
            try (PreparedStatement ps = conn.prepareStatement("SELECT nombre, qth FROM cb_directorio WHERE indicativo = ? AND eliminado = 0")) {
                ps.setString(1, txtCB(indicativoAntes));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (nom.isEmpty()) nom = txtCB(rs.getString(1));
                        if (qth.isEmpty()) qth = txtCB(rs.getString(2));
                    }
                }
            }
            upsertIndicativoCB(conn, ind, nom, qth);
            quitarDeListaCBSiNoQuedaEnRadioclub(conn, indicativoAntes);
        } catch (Exception e) {
            System.err.println("No se pudo actualizar la lista CB: " + e.getMessage());
        }
    }

    /** Si ese indicativo ya no tiene contactos de CB en el LOG Radioclub ni en actividades del radioclub, se quita de la lista BANDA CIUDADANA. */
    void quitarDeListaCBSiNoQuedaEnRadioclub(Connection conn, String indicativo) throws Exception {
        String ind = txtCB(indicativo);
        if (ind.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM qsos WHERE UPPER(TRIM(indicativo)) = ? AND UPPER(banda) = 'CB' AND (COALESCE(radioclub, 0) = 1 "
                + "OR actividad_numeral IN (SELECT numeral FROM actividades WHERE COALESCE(radioclub, 0) = 1 AND numeral > 0)) LIMIT 1")) {
            ps.setString(1, ind);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return; }   // aún quedan contactos del radioclub con ese indicativo
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE cb_directorio SET eliminado = 1, corte = ? WHERE indicativo = ? AND eliminado = 0")) {
            ps.setLong(1, maxIdQsos(conn));
            ps.setString(2, ind);
            ps.executeUpdate();
        }
    }

    /** Botón Actualizar: busca en todos los contactos CB los indicativos que faltan en la lista y los añade. */
    private void actualizarListaBandaCiudadana() {
        // Si se comparte con el radioclub, primero se recoge lo de los demás (altas, cambios y lo quitado por un superadministrador)
        if (radioclub.syncActivo() && btnActualizarCB != null && btnActualizarCB.isEnabled()) {
            btnActualizarCB.setEnabled(false);
            btnActualizarCB.setText("Actualizando…");
            Thread t = new Thread(() -> {
                try { radioclub.sincronizarRadioclub(); } finally {
                    SwingUtilities.invokeLater(() -> {
                        btnActualizarCB.setText("Actualizar base de datos");
                        btnActualizarCB.setEnabled(true);
                        actualizarListaBandaCiudadanaLocal();
                    });
                }
            }, "ActualizarListaCB");
            t.setDaemon(true);
            t.start();
            return;
        }
        actualizarListaBandaCiudadanaLocal();
    }

    private void actualizarListaBandaCiudadanaLocal() {
        int anadidos;
        try (Connection conn = DatabaseManager.conectar()) {
            // Lo que se quitó o se cambió a mano no vuelve (solo si hay contactos nuevos con ese indicativo)
            anadidos = sincronizarDirectorioCB(conn, false);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error al actualizar la lista: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        cargarVistaBandaCiudadana();
        JOptionPane.showMessageDialog(this,
                anadidos > 0
                        ? "Se han añadido " + anadidos + (anadidos == 1 ? " indicativo" : " indicativos")
                        + " de tus contactos en CB que faltaban en la lista."
                        : "La lista ya contiene todos los indicativos de tus contactos en CB.",
                "Actualizar lista", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Lista de indicativos de banda ciudadana: QRZ, QRA (nombre) y QTH, sin repetir y editable. */
    private void cargarVistaBandaCiudadana() {
        List<String[]> filas = new ArrayList<>();
        try (Connection conn = DatabaseManager.conectar()) {
            sincronizarDirectorioCB(conn);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT indicativo, nombre, qth FROM cb_directorio WHERE eliminado = 0 ORDER BY indicativo ASC")) {
                while (rs.next()) {
                    filas.add(new String[]{txtCB(rs.getString(1)), txtCB(rs.getString(2)), txtCB(rs.getString(3))});
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error al cargar la banda ciudadana: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        cargandoCB = true;
        try {
            modeloCB.setRowCount(0);
            for (String[] f : filas) {
                modeloCB.addRow(new Object[]{f[0], f[1], f[2], f[0]});
            }
        } finally {
            cargandoCB = false;
        }

        if (tablaQSOs.getModel() != modeloCB) {
            tablaQSOs.clearSelection();   // la fila elegida en otra vista no vale aquí
            tablaQSOs.setModel(modeloCB);
        }
        if (tablaQSOs.getColumnModel().getColumnCount() > 3) {
            tablaQSOs.removeColumn(tablaQSOs.getColumnModel().getColumn(3));
        }
        mostrandoVistaPuntos = false;
        mostrandoVistaCB = true;
        aplicarFiltroCB();
        actualizarEstado();
    }

    long maxIdQsos(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM qsos")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private void configurarEdicionCB() {
        modeloCB.addTableModelListener(e -> {
            if (cargandoCB || e.getType() != javax.swing.event.TableModelEvent.UPDATE) return;
            int row = e.getFirstRow();
            int col = e.getColumn();
            if (row < 0 || row >= modeloCB.getRowCount() || col < 0 || col > 2) return;

            if (!radioclub.pedirPermisoRC(this, "LISTACB", "cambiar contactos en BANDA CIUDADANA")) {   // sin permiso: se deja como estaba
                SwingUtilities.invokeLater(this::cargarVistaBandaCiudadana);
                return;
            }
            String clave = txtCB(modeloCB.getValueAt(row, 3));
            String ind = txtCB(modeloCB.getValueAt(row, 0));
            String nombre = txtCB(modeloCB.getValueAt(row, 1));
            String qth = txtCB(modeloCB.getValueAt(row, 2));
            guardarEdicionCB(clave, ind, nombre, qth, col);
            SwingUtilities.invokeLater(this::cargarVistaBandaCiudadana);
        });
    }

    /** Quita tildes y pasa a mayúsculas, para buscar sin importar cómo se escriba. */
    static String sinTildes(String t) {
        if (t == null) return "";
        return java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase();
    }

    /** Botones TODOS / HF / CB encima del log: qué contactos se ven. */
    private String verBanda = "TODOS";

    /** Filtra la tabla (cualquier LOG o actividad): filas en las que alguna columna contenga lo escrito en "Buscar". */
    private void aplicarFiltroCB() {
        if (tablaQSOs == null) return;
        if (!(tablaQSOs.getRowSorter() instanceof javax.swing.table.TableRowSorter<?> sorter)) return;
        @SuppressWarnings("unchecked")
        javax.swing.table.TableRowSorter<javax.swing.table.TableModel> srt = (javax.swing.table.TableRowSorter<javax.swing.table.TableModel>) sorter;
        String buscado = txtBuscarCB == null ? "" : sinTildes(txtBuscarCB.getText().trim());
        tablaQSOs.clearSelection();
        // Columna "Banda" de los logs (el directorio de BANDA CIUDADANA no tiene: ahí no se filtra por banda)
        int colBanda = tablaQSOs.getModel() == modeloCB || tablaQSOs.getModel() instanceof javax.swing.table.AbstractTableModel == false ? -1
                : ((javax.swing.table.AbstractTableModel) tablaQSOs.getModel()).findColumn("Banda");
        final boolean porBanda = colBanda >= 0 && !"TODOS".equals(verBanda);
        if (buscado.isEmpty() && !porBanda) {
            srt.setRowFilter(null);
        } else {
            srt.setRowFilter(new RowFilter<javax.swing.table.TableModel, Integer>() {
                @Override public boolean include(Entry<? extends javax.swing.table.TableModel, ? extends Integer> e) {
                    if (porBanda) {
                        boolean esCB = "CB".equalsIgnoreCase(e.getStringValue(colBanda).trim());
                        if ("CB".equals(verBanda) != esCB) return false;
                    }
                    if (buscado.isEmpty()) return true;
                    int columnas = e.getModel() == modeloCB ? 3 : e.getValueCount();
                    for (int c = 0; c < columnas; c++) if (sinTildes(e.getStringValue(c)).contains(buscado)) return true;
                    return false;
                }
            });
        }
        actualizarEstado();
    }

    /** Ventana para cambiar el indicativo, el nombre o el QTH de un contacto de BANDA CIUDADANA. */
    private void editarContactoCB() {
        if (tablaQSOs.getModel() != modeloCB) return;
        int fila = tablaQSOs.getSelectedRow();
        if (fila < 0) {
            JOptionPane.showMessageDialog(this, "Selecciona primero un contacto de la lista.", "Editar contacto", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!radioclub.pedirPermisoRC(this, "LISTACB", "cambiar contactos en BANDA CIUDADANA")) return;
        int m = tablaQSOs.convertRowIndexToModel(fila);
        String clave = txtCB(modeloCB.getValueAt(m, 3));
        JTextField tInd = new JTextField(txtCB(modeloCB.getValueAt(m, 0)), 20);
        JTextField tNom = new JTextField(txtCB(modeloCB.getValueAt(m, 1)), 20);
        JTextField tQth = new JTextField(txtCB(modeloCB.getValueAt(m, 2)), 20);
        for (JTextField t : new JTextField[]{tInd, tNom, tQth}) aplicarFiltroMayusculas(t);
        JPanel p = new JPanel(new GridLayout(3, 2, 6, 6));
        p.add(new JLabel("QRZ (indicativo):")); p.add(tInd);
        p.add(new JLabel("QRA (nombre):")); p.add(tNom);
        p.add(new JLabel("QTH:")); p.add(tQth);
        int r = JOptionPane.showConfirmDialog(this, p, "Editar contacto", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        String ind = tInd.getText().trim().toUpperCase();
        if (ind.isEmpty()) {
            JOptionPane.showMessageDialog(this, "El indicativo no puede quedar vacío.", "Editar contacto", JOptionPane.WARNING_MESSAGE);
            return;
        }
        guardarEdicionCB(clave, ind, tNom.getText().trim().toUpperCase(), tQth.getText().trim().toUpperCase(), ind.equals(clave) ? 1 : 0);
        cargarVistaBandaCiudadana();
        aplicarFiltroCB();
        // Volver a seleccionar el contacto editado
        for (int v = 0; v < tablaQSOs.getRowCount(); v++) {
            if (ind.equals(txtCB(tablaQSOs.getValueAt(v, 0)))) { tablaQSOs.setRowSelectionInterval(v, v); tablaQSOs.scrollRectToVisible(tablaQSOs.getCellRect(v, 0, true)); break; }
        }
    }

    private void guardarEdicionCB(String clave, String ind, String nombre, String qth, int col) {
        try (Connection conn = DatabaseManager.conectar()) {
            if (col == 0 && !ind.equals(clave)) {
                if (ind.isEmpty()) return;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM cb_directorio WHERE indicativo = ? AND eliminado = 0")) {
                    ps.setString(1, ind);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            JOptionPane.showMessageDialog(this, "El indicativo " + ind + " ya está en la lista.",
                                    "Indicativo repetido", JOptionPane.WARNING_MESSAGE);
                            return;
                        }
                    }
                }
                long corte = maxIdQsos(conn);
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE cb_directorio SET eliminado = 1, corte = ? WHERE indicativo = ?")) {
                        ps.setLong(1, corte);
                        ps.setString(2, clave);
                        ps.executeUpdate();
                    }
                    upsertIndicativoCB(conn, ind, nombre, qth);
                    conn.commit();
                } catch (Exception ex) {
                    conn.rollback();
                    throw ex;
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE cb_directorio SET nombre = ?, qth = ? WHERE indicativo = ?")) {
                    ps.setString(1, nombre);
                    ps.setString(2, qth);
                    ps.setString(3, clave);
                    ps.executeUpdate();
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error al guardar el cambio: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void upsertIndicativoCB(Connection conn, String ind, String nombre, String qth) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO cb_directorio (indicativo, nombre, qth, eliminado, corte) VALUES (?, ?, ?, 0, 0) " +
                        "ON CONFLICT(indicativo) DO UPDATE SET nombre = excluded.nombre, qth = excluded.qth, eliminado = 0")) {
            ps.setString(1, ind);
            ps.setString(2, nombre);
            ps.setString(3, qth);
            ps.executeUpdate();
        }
    }

    private void agregarIndicativoCB() {
        JTextField txtInd = new JTextField(20);
        JTextField txtNom = new JTextField(20);
        JTextField txtQ = new JTextField(20);
        aplicarFiltroMayusculas(txtInd);
        aplicarFiltroMayusculas(txtNom);
        aplicarFiltroMayusculas(txtQ);

        Object[] contenido = {"Indicativo (QRZ):", txtInd, "Nombre (QRA):", txtNom, "QTH:", txtQ};
        while (true) {
            int resp = JOptionPane.showConfirmDialog(this, contenido, "Agregar contacto a Banda Ciudadana",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (resp != JOptionPane.OK_OPTION) return;

            String ind = txtCB(txtInd.getText());
            if (ind.isEmpty()) {
                JOptionPane.showMessageDialog(this, "El indicativo es obligatorio.", "Aviso", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            try (Connection conn = DatabaseManager.conectar()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM cb_directorio WHERE indicativo = ? AND eliminado = 0")) {
                    ps.setString(1, ind);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            JOptionPane.showMessageDialog(this, "El indicativo " + ind + " ya está en la lista.",
                                    "Indicativo repetido", JOptionPane.WARNING_MESSAGE);
                            continue;
                        }
                    }
                }
                upsertIndicativoCB(conn, ind, txtCB(txtNom.getText()), txtCB(txtQ.getText()));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error al agregar el indicativo: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            cargarVistaBandaCiudadana();
            return;
        }
    }

    /** Quita indicativos de la lista de CB. Los contactos del log no se tocan. */
    private void eliminarIndicativosCB() {
        int[] filas = tablaQSOs.getSelectedRows();
        if (filas.length == 0) return;
        if (!radioclub.pedirPermisoRC(this, "LISTACB", "borrar contactos de BANDA CIUDADANA")) return;

        List<String> claves = new ArrayList<>();
        for (int f : filas) {
            int m = tablaQSOs.convertRowIndexToModel(f);
            claves.add(txtCB(modeloCB.getValueAt(m, 3)));
        }

        int resp = JOptionPane.showConfirmDialog(this,
                "¿Quitar " + (claves.size() == 1 ? "el indicativo " + claves.get(0) : claves.size() + " indicativos")
                        + " de la lista de Banda Ciudadana?\n\nLos contactos del log no se borran.",
                "Confirmar", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (resp != JOptionPane.YES_OPTION) return;

        try (Connection conn = DatabaseManager.conectar()) {
            long corte = maxIdQsos(conn);
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE cb_directorio SET eliminado = 1, corte = ? WHERE indicativo = ?")) {
                for (String clave : claves) {
                    ps.setLong(1, corte);
                    ps.setString(2, clave);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error al quitar de la lista: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
        cargarVistaBandaCiudadana();
    }

    /** Añade muchas filas a la tabla avisándola una sola vez (no una vez por fila): mucho más rápido con LOG grandes. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void anadirFilasDeGolpe(DefaultTableModel m, List<Object[]> filas) {
        if (filas.isEmpty()) return;
        java.util.Vector datos = m.getDataVector();
        int columnas = m.getColumnCount();
        for (Object[] f : filas) {
            java.util.Vector<Object> v = new java.util.Vector<>(columnas);
            for (int i = 0; i < columnas; i++) v.add(i < f.length ? f[i] : null);
            datos.add(v);
        }
        m.fireTableDataChanged();
    }

    private void cargarVistaEstandar(int numeralActividad, boolean filtrarPorActividad) {
        if (mostrandoVistaPuntos || mostrandoVistaCB) {
            tablaQSOs.setModel(modeloTabla);
            restaurarColumnasEstandar();
            mostrandoVistaPuntos = false;
            mostrandoVistaCB = false;
        }

        modeloTabla.setRowCount(0);

        boolean vistaRC = !filtrarPorActividad && radioclub.esVistaRadioclubSeleccionada();

        String sql;
        if (filtrarPorActividad && numeralActividad > 0) {
            sql = "SELECT id, indicativo, fecha, hora, banda, modo, " +
                    "freq_tx, freq_rx, rst_sent, rst_rcvd, nombre, qth, grid, actividad, operador, estacion, COALESCE(modificado_por, '') AS modificado_por, COALESCE(comentario, '') AS comentario, COALESCE(b26_conf, '') AS b26_conf, COALESCE(b26_qsl, 0) AS b26_qsl " +
                    "FROM qsos WHERE actividad_numeral = ? " +
                    "ORDER BY substr(fecha, 7, 4) ASC, substr(fecha, 4, 2) ASC, substr(fecha, 1, 2) ASC, hora ASC";
        } else if (vistaRC) {
            // LOG Radioclub: todos los contactos marcados como del radioclub, de cualquier operador
            sql = "SELECT id, indicativo, fecha, hora, banda, modo, " +
                    "freq_tx, freq_rx, rst_sent, rst_rcvd, nombre, qth, grid, actividad, operador, estacion, COALESCE(modificado_por, '') AS modificado_por, COALESCE(comentario, '') AS comentario, COALESCE(b26_conf, '') AS b26_conf, COALESCE(b26_qsl, 0) AS b26_qsl " +
                    "FROM qsos WHERE radioclub = 1 " +
                    "ORDER BY substr(fecha, 7, 4) ASC, substr(fecha, 4, 2) ASC, substr(fecha, 1, 2) ASC, hora ASC";
        } else {
            sql = "SELECT id, indicativo, fecha, hora, banda, modo, " +
                    "freq_tx, freq_rx, rst_sent, rst_rcvd, nombre, qth, grid, actividad, operador, estacion, COALESCE(modificado_por, '') AS modificado_por, COALESCE(comentario, '') AS comentario, COALESCE(b26_conf, '') AS b26_conf, COALESCE(b26_qsl, 0) AS b26_qsl " +
                    "FROM qsos WHERE operador = ? " +
                    "ORDER BY substr(fecha, 7, 4) ASC, substr(fecha, 4, 2) ASC, substr(fecha, 1, 2) ASC, hora ASC";
        }

        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            if (filtrarPorActividad && numeralActividad > 0) {
                pstmt.setInt(1, numeralActividad);
            } else if (!vistaRC) {
                pstmt.setString(1, operadorActual);
            }

            // Las filas se juntan y se pasan a la tabla de una vez: con miles de contactos, añadirlas
            // una a una hacía que la tabla se reordenara a cada fila (más de 1 segundo con 16.000 contactos)
            List<Object[]> filasLeidas = new ArrayList<>();
            try (ResultSet rs = pstmt.executeQuery()) {
                int numeral = 1;
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String indicativo = rs.getString("indicativo");
                    String fecha = rs.getString("fecha");
                    String hora = rs.getString("hora");
                    if (horaLocal()) {
                        String[] l = convertirFechaHora(fecha, hora, true);
                        fecha = l[0];
                        hora = l[1];
                    }
                    String banda = rs.getString("banda");
                    String modo = rs.getString("modo");
                    String freqTx = rs.getString("freq_tx");
                    String freqRx = rs.getString("freq_rx");
                    String rstSent = rs.getString("rst_sent");
                    String rstRcvd = rs.getString("rst_rcvd");
                    String nombre = rs.getString("nombre");
                    String qth = rs.getString("qth");
                    String grid = rs.getString("grid");
                    String actividadInterna = rs.getString("actividad");
                    String actividadVisible = nombreVisibleDeActividad(actividadInterna);
                    String opRow = rs.getString("operador");
                    String estRow = rs.getString("estacion");
                    String operadorVisible = (opRow == null ? "" : opRow)
                            + (estRow == null || estRow.isEmpty() ? "" : " (" + estRow + ")");

                    filasLeidas.add(new Object[]{
                            numeral++,
                            indicativo,
                            fecha,
                            hora,
                            banda,
                            modo,
                            freqParaTabla(freqTx),
                            freqParaTabla(freqRx),
                            rstSent,
                            rstRcvd,
                            nombre,
                            qth,
                            grid,
                            actividadVisible,
                            id,
                            operadorVisible,
                            rs.getString("modificado_por"),
                            rs.getString("comentario"),
                            cuentaB26.textoConfirmacion(rs.getString("b26_conf"), rs.getInt("b26_qsl"))
                    });
                }
            }
            anadirFilasDeGolpe(modeloTabla, filasLeidas);
            mostrarColumnaOperador(vistaRC);
            mostrarColumnaModificado(vistaRC || (filtrarPorActividad && numeralActividad > 0));
            boolean actividadCB = filtrarPorActividad && numeralActividad > 0
                    && "CB".equalsIgnoreCase(String.valueOf(obtenerBandaSimpleDeActividad(numeralActividad)));
            ordenarColumnasCB(actividadCB);
            aplicarAnchosColumnas();

            if (!filtrarPorActividad) {
                javax.swing.RowSorter<?> sorter = tablaQSOs.getRowSorter();
                if (sorter != null) {
                    sorter.setSortKeys(java.util.List.of(
                            new javax.swing.RowSorter.SortKey(0, javax.swing.SortOrder.DESCENDING)));
                }
            }

            actualizarEstado();

        } catch (Exception e) {
            System.err.println("Error al cargar vista estándar: " + e.getMessage());
        }
    }

    /** Anchos de las columnas de la tabla (como en la captura del usuario). Solo al crearse las columnas. */
    private javax.swing.table.TableColumn columnaAnchosAplicados;
    private static final int[] ANCHOS_ESTANDAR = {48, 135, 100, 83, 65, 93, 126, 126, 80, 78, 288, 257, 116, 318};
    private static final int[] ANCHOS_PUNTOS = {48, 135, 288, 257, 116, 318, 110, 90};

    private void aplicarAnchosColumnas() {
        if (tablaQSOs == null) return;
        javax.swing.table.TableColumnModel tcm = tablaQSOs.getColumnModel();
        if (tcm.getColumnCount() == 0 || tcm.getColumn(0) == columnaAnchosAplicados) return;
        int[] anchos = tablaQSOs.getModel() == modeloPuntos ? ANCHOS_PUNTOS : ANCHOS_ESTANDAR;
        for (int v = 0; v < tcm.getColumnCount(); v++) {
            javax.swing.table.TableColumn c = tcm.getColumn(v);
            int mi = c.getModelIndex();
            if (mi < anchos.length && c.getMaxWidth() > 0) c.setPreferredWidth(anchos[mi]);
            if (anchos == ANCHOS_ESTANDAR && mi == COL_ESTANDAR_COMENTARIO) c.setPreferredWidth(300);
            if (anchos == ANCHOS_ESTANDAR && mi == 1) c.setCellRenderer(rendererIndicativoConEstrella());
            if (anchos == ANCHOS_ESTANDAR && mi == COL_ESTANDAR_CONFIRMACION) {
                c.setPreferredWidth(150);
                // La estrella se pinta con la letra "Dialog" (en Windows, Arial no la tiene)
                c.setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
                    @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int col) {
                        Component comp = super.getTableCellRendererComponent(t, v, sel, foco, r, col);
                        comp.setFont(new Font("Dialog", Font.BOLD, t.getFont().getSize()));
                        String txt = v == null ? "" : v.toString();
                        boolean confirmado = txt.startsWith("★");
                        setText(confirmado ? txt.substring(1).trim() : txt);   // la estrella va ahora junto al indicativo
                        if (!sel) comp.setForeground(confirmado ? TEMA_AMBAR : new Color(120, 130, 145));
                        return comp;
                    }
                });
            }
        }
        columnaAnchosAplicados = tcm.getColumn(0);
    }

    /** Indicativo a la izquierda y la estrella de confirmación a la derecha (gris = sin confirmar, ámbar = confirmado). */
    private javax.swing.table.TableCellRenderer rendererIndicativo;

    private javax.swing.table.TableCellRenderer rendererIndicativoConEstrella() {
        if (rendererIndicativo != null) return rendererIndicativo;
        javax.swing.table.DefaultTableCellRenderer base = new javax.swing.table.DefaultTableCellRenderer();
        JLabel estrella = new JLabel("★");
        estrella.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 6));
        JPanel celda = new JPanel(new BorderLayout());
        celda.setOpaque(true);
        rendererIndicativo = (t, v, sel, foco, fila, col) -> {
            Component comp = base.getTableCellRendererComponent(t, v, sel, foco, fila, col);
            String conf = "";
            try {
                if (t.getModel() == modeloTabla && modeloTabla.getColumnCount() > COL_ESTANDAR_CONFIRMACION) {
                    Object o = modeloTabla.getValueAt(t.convertRowIndexToModel(fila), COL_ESTANDAR_CONFIRMACION);
                    conf = o == null ? "" : o.toString();
                }
            } catch (Exception ignored) { }
            boolean confirmado = conf.startsWith("★");
            estrella.setFont(new Font("Dialog", Font.BOLD, t.getFont().getSize() + 1));   // en Windows, Arial no tiene la estrella
            estrella.setForeground(confirmado ? TEMA_AMBAR : new Color(120, 130, 145));
            celda.setToolTipText(confirmado ? "Confirmado por la otra estación (Bitácora 26)" : "Sin confirmar");
            celda.removeAll();
            celda.add(comp, BorderLayout.CENTER);
            celda.add(estrella, BorderLayout.EAST);
            celda.setBackground(comp.getBackground());
            return celda;
        };
        return rendererIndicativo;
    }

    private void restaurarColumnasEstandar() {
        if (tablaQSOs.getColumnModel().getColumnCount() > 0) {
            tablaQSOs.getColumnModel().getColumn(0).setCellEditor(null);
        }
        if (tablaQSOs.getColumnModel().getColumnCount() > COL_ESTANDAR_ID) {
            tablaQSOs.getColumnModel().getColumn(COL_ESTANDAR_ID).setMinWidth(0);
            tablaQSOs.getColumnModel().getColumn(COL_ESTANDAR_ID).setMaxWidth(0);
            tablaQSOs.getColumnModel().getColumn(COL_ESTANDAR_ID).setWidth(0);
        }
    }

    /**
     * En las actividades de CB la tabla va: Numeral, Indicativo, QRA, QTH y luego el resto como siempre.
     * En las demás vistas, el orden de siempre.
     */
    private void ordenarColumnasCB(boolean cb) {
        if (tablaQSOs == null || tablaQSOs.getModel() != modeloTabla) return;
        javax.swing.table.TableColumnModel tcm = tablaQSOs.getColumnModel();
        if (tcm.getColumnCount() != COLUMNAS_ESTANDAR.length) return;
        int[] orden = cb ? new int[]{0, 1, 10, 11, 2, 3, 4, 5, 6, 7, 8, 9, 12, 13, 14, 15, 16, 17, 18}
                : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18};
        for (int destino = 0; destino < orden.length; destino++) {
            int actual = -1;
            for (int v = 0; v < tcm.getColumnCount(); v++) {
                if (tcm.getColumn(v).getModelIndex() == orden[destino]) { actual = v; break; }
            }
            if (actual >= 0 && actual != destino) tcm.moveColumn(actual, destino);
        }
        for (int v = 0; v < tcm.getColumnCount(); v++) {
            if (tcm.getColumn(v).getModelIndex() == 10) tcm.getColumn(v).setHeaderValue(cb ? "QRA" : COLUMNAS_ESTANDAR[10]);
        }
        tablaQSOs.getTableHeader().repaint();
    }

    /** La columna "Modificado" se ve en el LOG Radioclub y en las actividades. */
    private void mostrarColumnaModificado(boolean visible) {
        if (tablaQSOs == null || tablaQSOs.getModel() != modeloTabla) return;
        javax.swing.table.TableColumnModel tcm = tablaQSOs.getColumnModel();
        for (int v = 0; v < tcm.getColumnCount(); v++) {
            javax.swing.table.TableColumn col = tcm.getColumn(v);
            if (col.getModelIndex() != COL_ESTANDAR_MODIFICADO) continue;
            if (visible) {
                col.setMinWidth(15);
                col.setMaxWidth(Integer.MAX_VALUE);
                if (col.getWidth() < 15) { col.setPreferredWidth(110); col.setWidth(110); }
            } else {
                col.setMinWidth(0);
                col.setMaxWidth(0);
                col.setWidth(0);
            }
        }
    }

    /** La columna "Operador (Indicativo)" solo se ve en el LOG Radioclub. */
    private void mostrarColumnaOperador(boolean visible) {
        if (tablaQSOs == null || tablaQSOs.getModel() != modeloTabla) return;
        if (tablaQSOs.getColumnModel().getColumnCount() <= COL_ESTANDAR_OPERADOR) return;
        javax.swing.table.TableColumn col = tablaQSOs.getColumnModel().getColumn(COL_ESTANDAR_OPERADOR);
        if (visible) {
            col.setMinWidth(15);
            col.setMaxWidth(Integer.MAX_VALUE);
            col.setPreferredWidth(170);
            col.setWidth(170);
        } else {
            col.setMinWidth(0);
            col.setMaxWidth(0);
            col.setWidth(0);
        }
    }

    private void cargarVistaPuntos(int numeralActividad) {
        mostrandoVistaCB = false;
        String nombreInterno = obtenerNombreInternoDeActividad(numeralActividad);
        String nombreVisible = nombreVisibleDeActividad(nombreInterno);

        String sql = "SELECT indicativo, fecha, hora, nombre, qth, grid, puntos, num_contactos, banda " +
                "FROM qsos WHERE actividad_numeral = ? ORDER BY substr(fecha, 7, 4) ASC, substr(fecha, 4, 2) ASC, substr(fecha, 1, 2) ASC, hora ASC";
        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, numeralActividad);

            Map<String, FilaPuntos> acumulado = new LinkedHashMap<>();

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String indicativo = rs.getString("indicativo");
                    String fecha = rs.getString("fecha");
                    String hora = rs.getString("hora");
                    String nombre = rs.getString("nombre");
                    String qth = rs.getString("qth");
                    String grid = rs.getString("grid");
                    int puntosQso = rs.getInt("puntos");
                    int contactosQso = rs.getInt("num_contactos");

                    if (indicativo == null || indicativo.isEmpty()) continue;

                    // En CB, mismo indicativo con distinto QRA o QTH = otra persona (otra fila)
                    String clave = indicativo;
                    if ("CB".equalsIgnoreCase(rs.getString("banda"))) {
                        clave = indicativo + "|" + (nombre == null ? "" : nombre.trim().toUpperCase())
                                + "|" + (qth == null ? "" : qth.trim().toUpperCase());
                    }
                    FilaPuntos acum = acumulado.get(clave);
                    if (acum == null) {
                        acum = new FilaPuntos(
                                indicativo,
                                nombre != null ? nombre : "",
                                qth != null ? qth : "",
                                grid != null ? grid : "",
                                nombreVisible,
                                contactosQso,
                                puntosQso,
                                fecha != null ? fecha : "",
                                hora != null ? hora : ""
                        );
                        acumulado.put(clave, acum);
                    } else {
                        acum.numContactos += contactosQso;
                        acum.puntos += puntosQso;
                        if ((acum.nombre == null || acum.nombre.isEmpty()) && nombre != null && !nombre.isEmpty()) {
                            acum.nombre = nombre;
                        }
                        if ((acum.qth == null || acum.qth.isEmpty()) && qth != null && !qth.isEmpty()) {
                            acum.qth = qth;
                        }
                        if ((acum.grid == null || acum.grid.isEmpty()) && grid != null && !grid.isEmpty()) {
                            acum.grid = grid;
                        }
                    }
                }
            }

            List<FilaPuntos> lista = new ArrayList<>(acumulado.values());
            lista.sort((a, b) -> {
                int cmp = Integer.compare(b.puntos, a.puntos);
                if (cmp != 0) return cmp;
                cmp = Integer.compare(b.numContactos, a.numContactos);
                if (cmp != 0) return cmp;
                cmp = compararFecha(a.fechaDesempate, b.fechaDesempate);
                if (cmp != 0) return cmp;
                return compararHora(a.horaDesempate, b.horaDesempate);
            });

            modeloPuntos.setRowCount(0);
            int numeral = 1;
            for (FilaPuntos f : lista) {
                modeloPuntos.addRow(new Object[]{
                        numeral++,
                        f.indicativo,
                        f.nombre,
                        f.qth,
                        f.grid,
                        f.actividad,
                        f.numContactos,
                        f.puntos
                });
            }

            tablaQSOs.setModel(modeloPuntos);
            mostrandoVistaPuntos = true;
            aplicarAnchosColumnas();

            actualizarEstado();
        } catch (Exception e) {
            System.err.println("Error al cargar vista PUNTOS: " + e.getMessage());
        }
    }

    private int compararFecha(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        try {
            String[] pa = a.split("/");
            String[] pb = b.split("/");
            if (pa.length != 3 || pb.length != 3) return a.compareTo(b);
            String ia = pa[2] + pa[1] + pa[0];
            String ib = pb[2] + pb[1] + pb[0];
            return ia.compareTo(ib);
        } catch (Exception e) {
            return a.compareTo(b);
        }
    }

    private int compararHora(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    void actualizarEstado() {
        int total = modeloTabla.getRowCount();
        String estadoCat;
        if (catConectado) {
            estadoCat = "CAT OmniRig: Conectado (Rig " + rigSeleccionado + ")";
        } else {
            estadoCat = "CAT OmniRig: Desconectado (Rig " + rigSeleccionado + " seleccionado)";
        }

        // Nombre de lo que se está viendo (sin el número de la actividad: "4 - BANDA Y MODO" -> "BANDA Y MODO")
        Object sel = cbSelectorActividad == null ? null : cbSelectorActividad.getSelectedItem();
        String vista = sel == null ? "LOG Principal" : sel.toString().replaceFirst("^\\d+\\s*-\\s*", "");
        if (mostrandoVistaCB) {
            lblEstado.setText("Total indicativos en el Directorio de Indicativos: " + modeloCB.getRowCount() + " | " + estadoCat);
        } else if (mostrandoVistaPuntos) {
            int totalIndicativos = modeloPuntos.getRowCount();
            lblEstado.setText("Total indicativos en " + vista + ": " + totalIndicativos + " | " + estadoCat);
        } else {
            lblEstado.setText("Total QSOs en " + vista + ": " + total + " | " + estadoCat);
        }
    }

    // =====================================================================
    // MAPA: distancia y rumbo desde mi QTH hasta el del corresponsal
    // =====================================================================

    /** Convierte un locator Maidenhead (4, 6 u 8 caracteres) en {lat, lon} del centro de la cuadrícula. */
    static double[] gridALatLon(String grid) {
        if (grid == null) return null;
        String g = grid.trim().toUpperCase();
        if (!g.matches("[A-R]{2}[0-9]{2}([A-X]{2}([0-9]{2})?)?")) return null;
        double lon = (g.charAt(0) - 'A') * 20 - 180;
        double lat = (g.charAt(1) - 'A') * 10 - 90;
        lon += (g.charAt(2) - '0') * 2;
        lat += (g.charAt(3) - '0');
        double tamLon = 2, tamLat = 1;
        if (g.length() >= 6) {
            lon += (g.charAt(4) - 'A') * (2.0 / 24);
            lat += (g.charAt(5) - 'A') * (1.0 / 24);
            tamLon = 2.0 / 24;
            tamLat = 1.0 / 24;
        }
        if (g.length() >= 8) {
            lon += (g.charAt(6) - '0') * (2.0 / 240);
            lat += (g.charAt(7) - '0') * (1.0 / 240);
            tamLon = 2.0 / 240;
            tamLat = 1.0 / 240;
        }
        return new double[]{lat + tamLat / 2, lon + tamLon / 2};
    }

    /** Locator de 6 caracteres a partir de lat/lon (para mostrarlo cuando la posición viene del QTH). */
    static String latLonAGrid(double lat, double lon) {
        double x = lon + 180, y = lat + 90;
        char a = (char) ('A' + (int) (x / 20)), b = (char) ('A' + (int) (y / 10));
        x %= 20; y %= 10;
        char c = (char) ('0' + (int) (x / 2)), d = (char) ('0' + (int) y);
        x %= 2; y %= 1;
        char e = (char) ('a' + (int) (x * 12)), f = (char) ('a' + (int) (y * 24));
        return "" + a + b + c + d + e + f;
    }

    /**
     * Busca las coordenadas de un QTH (ciudad, pueblo, región...) en OpenStreetMap.
     * Cada QTH se guarda en la base de datos (tabla geo_cache): solo se pregunta a internet una vez.
     * Devuelve {lat, lon} o null si no se encuentra o no hay internet. NO llamar desde la ventana (es lento).
     */
    static double[] buscarCoordenadasQth(String qth) {
        return buscarCoordenadasQth(qth, null);
    }

    /** Variantes del QTH para buscar: completo, sin códigos postales ni "P.O.BOX", y solo la primera parte. */
    static List<String> variantesQth(String qth) {
        List<String> v = new ArrayList<>();
        String t = qth.trim().toUpperCase();
        v.add(t);
        String limpio = t.replaceAll("\\([^)]*\\)", " ")                                  // (MB), (CADIZ)...
                .replaceAll("P\\.?\\s*O\\.?\\s*BOX\\s*[-:]?", " ")                      // P.O.BOX
                .replaceAll("\\b[0-9]{4}\\s?[A-Z]{2}\\b", " ")                         // código postal holandés 7534GM
                .replaceAll("\\b[0-9]+[0-9\\-]*\\.?(?=\\s|,|$)", " ")                   // números y códigos postales
                .replaceAll("\\s*,\\s*", ", ")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("^[ ,.\\-]+|[ ,.\\-]+$", "")
                .replaceAll("^(?:[,.\\-]\\s*)+", "")
                .trim();
        if (!limpio.isEmpty() && !v.contains(limpio)) v.add(limpio);
        int coma = limpio.indexOf(',');
        if (coma > 1) {
            String primera = limpio.substring(0, coma).trim();
            if (primera.length() >= 2 && !v.contains(primera)) v.add(primera);
        }
        return v;
    }

    /**
     * Busca las coordenadas de un QTH. "iso" es el país (sacado del indicativo, p. ej. "CN"), o null.
     * Primero en ese país (con el texto completo, limpio y solo la primera parte); después en todo el mundo.
     */
    static double[] buscarCoordenadasQth(String qth, String iso) {
        return buscarCoordenadasQth(qth, iso, false);
    }

    /** @param preguntar si es true y el lugar está en varios países, se pregunta cuál es */
    static double[] buscarCoordenadasQth(String qth, String iso, boolean preguntar) {
        LugarEncontrado l = buscarLugarQth(qth, iso, preguntar);
        return l == null ? null : new double[]{l.lat(), l.lon()};
    }

    /** Como buscarCoordenadasQth, pero devuelve también el país (ISO) del lugar encontrado. */
    static LugarEncontrado buscarLugarQth(String qth, String iso, boolean preguntar) {
        if (qth == null) return null;
        String texto = qth.trim().toUpperCase();
        if (texto.length() < 2) return null;
        String pais = (iso == null || iso.isBlank()) ? "" : iso.trim().toLowerCase();
        String clave = pais.toUpperCase() + "|" + texto;

        try (Connection conn = DatabaseManager.conectar()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT lat, lon, encontrado, COALESCE(pais, '') AS pais FROM geo_cache WHERE clave = ?")) {
                ps.setString(1, clave);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("encontrado") == 1
                                ? new LugarEncontrado(rs.getDouble("lat"), rs.getDouble("lon"), rs.getString("pais"), texto) : null;
                    }
                }
            }
            // Posiciones encontradas con la versión anterior (sin país): se aprovechan,
            // salvo cuando hay que preguntar (CB), porque entonces se buscaba solo en España
            if (!preguntar) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT lat, lon, COALESCE(pais, '') AS pais FROM geo_cache WHERE clave = ? AND encontrado = 1")) {
                    ps.setString(1, texto);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) return new LugarEncontrado(rs.getDouble("lat"), rs.getDouble("lon"), rs.getString("pais"), texto);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        LugarEncontrado res = null;
        try {
            List<String> variantes = variantesQth(texto);
            if (!pais.isEmpty()) {
                for (String v : variantes) {
                    List<LugarEncontrado> enPais = consultarNominatimLista(v, pais);
                    if (!enPais.isEmpty()) {
                        res = enPais.get(0);
                        break;
                    }
                }
            }
            if (res == null) {
                // En todo el mundo: texto completo y después ya limpio (p. ej. "LISBOA, PORTUGAL")
                for (int i = 0; i < Math.min(2, variantes.size()) && res == null; i++) {
                    List<LugarEncontrado> lugares = consultarNominatimLista(variantes.get(i), null);
                    if (lugares.isEmpty()) continue;
                    LugarEncontrado elegido = lugares.get(0);
                    if (lugares.size() > 1) {
                        if (!preguntar) return elegido;   // sin guardar: se preguntará otro día
                        elegido = elegirLugar(texto, lugares.subList(0, Math.min(8, lugares.size())));
                        if (elegido == null) return null;   // cancelado: no se guarda nada
                    }
                    res = elegido;
                }
            }
        } catch (Exception sinInternet) {
            return null; // sin internet: no se guarda nada, se reintentará otro día
        }

        try (Connection conn = DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR REPLACE INTO geo_cache (clave, lat, lon, encontrado, pais) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, clave);
            ps.setDouble(2, res == null ? 0 : res.lat());
            ps.setDouble(3, res == null ? 0 : res.lon());
            ps.setInt(4, res == null ? 0 : 1);
            ps.setString(5, res == null ? "" : res.pais());
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
        return res;
    }

    // OpenStreetMap: el programa se identifica siempre igual (lo piden sus normas de uso). Las direcciones de los
    // servidores se pueden cambiar en la configuración (MAPA_TESELAS_URL / MAPA_BUSQUEDA_URL) por si OpenStreetMap lo pide.
    static final String AGENTE_OSM = "Bitacora26/1.0 (logbook de radioaficionado; EA4IXR)";
    static String urlTeselasOsm() {
        String u = ConfigApp.get("MAPA_TESELAS_URL", "").trim();
        if (u.isEmpty()) return "https://tile.openstreetmap.org/";
        return u.endsWith("/") ? u : u + "/";
    }
    static String urlBusquedaOsm() {
        String u = ConfigApp.get("MAPA_BUSQUEDA_URL", "").trim();
        return u.isEmpty() ? "https://nominatim.openstreetmap.org/search" : u;
    }

    private static final Object BLOQUEO_NOMINATIM = new Object();
    private static long ultimaConsultaNominatim = 0;

    private static double[] consultarNominatim(String texto, String pais) throws Exception {
        synchronized (BLOQUEO_NOMINATIM) {
            // Normas de OpenStreetMap: como mucho una consulta por segundo
            long espera = 1100 - (System.currentTimeMillis() - ultimaConsultaNominatim);
            if (espera > 0) Thread.sleep(espera);
            ultimaConsultaNominatim = System.currentTimeMillis();

            String url = urlBusquedaOsm() + "?format=json&limit=1&accept-language=es"
                    + (pais == null ? "" : "&countrycodes=" + pais)
                    + "&q=" + URLEncoder.encode(texto, StandardCharsets.UTF_8);
            HttpClient cliente = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(6)).build();
            HttpRequest peticion = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(8))
                    .header("User-Agent", AGENTE_OSM)
                    .GET().build();
            HttpResponse<String> resp = cliente.send(peticion, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new java.io.IOException("HTTP " + resp.statusCode());
            String cuerpo = resp.body();
            Matcher mLat = Pattern.compile("\"lat\"\\s*:\\s*\"(-?[0-9.]+)\"").matcher(cuerpo);
            Matcher mLon = Pattern.compile("\"lon\"\\s*:\\s*\"(-?[0-9.]+)\"").matcher(cuerpo);
            if (mLat.find() && mLon.find()) {
                return new double[]{Double.parseDouble(mLat.group(1)), Double.parseDouble(mLon.group(1))};
            }
            return null;
        }
    }

    /** Un lugar encontrado: coordenadas, país (ISO) y nombre completo. */
    record LugarEncontrado(double lat, double lon, String pais, String nombre) {}

    /** Solo para pruebas: si no es null, se usa en lugar de internet (recibe texto y país, devuelve el JSON). */
    static java.util.function.BiFunction<String, String, String> fuenteNominatimPrueba = null;

    /** Varios lugares que coinciden con el texto (como mucho uno por país, en orden de importancia). */
    static List<LugarEncontrado> consultarNominatimLista(String texto, String pais) throws Exception {
        String cuerpo;
        if (fuenteNominatimPrueba != null) {
            cuerpo = fuenteNominatimPrueba.apply(texto, pais);
        } else {
            synchronized (BLOQUEO_NOMINATIM) {
                long espera = 1100 - (System.currentTimeMillis() - ultimaConsultaNominatim);
                if (espera > 0) Thread.sleep(espera);
                ultimaConsultaNominatim = System.currentTimeMillis();
                String url = urlBusquedaOsm() + "?format=json&limit=10&addressdetails=1&accept-language=es"
                        + (pais == null ? "" : "&countrycodes=" + pais)
                        + "&q=" + URLEncoder.encode(texto, StandardCharsets.UTF_8);
                HttpClient cliente = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(6)).build();
                HttpRequest peticion = HttpRequest.newBuilder(URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(8))
                        .header("User-Agent", AGENTE_OSM)
                        .GET().build();
                HttpResponse<String> resp = cliente.send(peticion, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) throw new java.io.IOException("HTTP " + resp.statusCode());
                cuerpo = resp.body();
            }
        }
        List<LugarEncontrado> lista = new ArrayList<>();
        Set<String> paisesVistos = new HashSet<>();
        if (cuerpo == null) return lista;
        Pattern pLat = Pattern.compile("\"lat\"\\s*:\\s*\"(-?[0-9.]+)\"");
        Pattern pLon = Pattern.compile("\"lon\"\\s*:\\s*\"(-?[0-9.]+)\"");
        Pattern pClase = Pattern.compile("\"class\"\\s*:\\s*\"([a-z_]+)\"");
        Pattern pNombre = Pattern.compile("\"display_name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Pattern pPais = Pattern.compile("\"country_code\"\\s*:\\s*\"([a-z]{2})\"");
        for (String trozo : cuerpo.split("\\{\\s*\"place_id\"")) {
            Matcher a = pLat.matcher(trozo), o = pLon.matcher(trozo);
            if (!a.find() || !o.find()) continue;
            Matcher c = pClase.matcher(trozo);
            String clase = c.find() ? c.group(1) : "";
            // Solo lugares (ciudades, pueblos, provincias, regiones...), no calles ni tiendas
            if (!clase.isEmpty() && !clase.equals("place") && !clase.equals("boundary")) continue;
            Matcher n = pNombre.matcher(trozo), pa = pPais.matcher(trozo);
            String nombre = n.find() ? desescaparJson(n.group(1)) : texto;
            String cc = pa.find() ? pa.group(1).toUpperCase() : "";
            if (!cc.isEmpty() && !paisesVistos.add(cc)) continue;   // uno por país
            lista.add(new LugarEncontrado(Double.parseDouble(a.group(1)), Double.parseDouble(o.group(1)), cc, nombre));
        }
        return lista;
    }

    private static String desescaparJson(String t) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '\\' && i + 1 < t.length()) {
                char sig = t.charAt(++i);
                if (sig == 'u' && i + 4 < t.length()) {
                    sb.append((char) Integer.parseInt(t.substring(i + 1, i + 5), 16));
                    i += 4;
                } else {
                    sb.append(sig == 'n' ? ' ' : sig);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** Si el lugar está en varios países, pregunta cuál es. Devuelve el elegido o null si se cancela. */
    private static LugarEncontrado elegirLugar(String qth, List<LugarEncontrado> opciones) {
        final LugarEncontrado[] elegido = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                String[] textos = new String[opciones.size()];
                for (int i = 0; i < opciones.size(); i++) textos[i] = opciones.get(i).nombre();
                JList<String> listaVisual = new JList<>(textos);
                listaVisual.setSelectedIndex(0);
                listaVisual.setVisibleRowCount(Math.min(8, textos.length));
                JPanel panel = new JPanel(new BorderLayout(0, 8));
                panel.add(new JLabel("<html>\"<b>" + qth + "</b>\" existe en varios países.<br>¿Cuál es el QTH del corresponsal?</html>"),
                        BorderLayout.NORTH);
                panel.add(new JScrollPane(listaVisual), BorderLayout.CENTER);
                panel.add(new JLabel("<html><font color='gray'>Se recordará tu elección para la próxima vez.</font></html>"),
                        BorderLayout.SOUTH);
                int r = JOptionPane.showConfirmDialog(null, panel, "¿Dónde está " + qth + "?",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (r == JOptionPane.OK_OPTION && listaVisual.getSelectedIndex() >= 0) {
                    elegido[0] = opciones.get(listaVisual.getSelectedIndex());
                }
            });
        } catch (Exception ignored) {
        }
        return elegido[0];
    }

    /** Panel con el mapa del mundo, la ruta, la distancia y el rumbo (SP / LP). */
    // ===================== CASCADA (RTL-SDR en la salida de FI de la emisora) =====================

    /**
     * Espectro y cascada. Recibe líneas de potencia (dB) ya calculadas y las dibuja.
     * La escala se pone con la frecuencia de la emisora en el centro.
     */
    static class PanelCascada extends JPanel {
        static final int ALTO_ESCALA = 14, ALTO_PIE = 14;
        /** Alto del espectro: más grande si la cascada es alta. */
        private int altoEsp() { return getHeight() >= 230 ? 74 : 46; }
        /** Alto total de la cascada (se cambia arrastrando su borde de abajo). */
        private int altoTotal = Math.max(130, Math.min(700, ConfigApp.getInt("CASCADA_ALTO", 280)));
        private boolean arrastrandoBorde = false;
        private int yInicioArrastre, altoInicioArrastre;
        // Vista desplazada: arrastrando con el botón izquierdo se ve otra zona SIN cambiar la frecuencia de la emisora
        static volatile double vistaHz = 0;
        private boolean arrastrandoVista = false, seMovioVista = false;
        private int xInicioVista;
        private double vistaInicio;
        /** Lo más que se puede desplazar la vista (el RTL-SDR recoge ±100 kHz alrededor de la FI). */
        double vistaMaxima() { return Math.max(0, 95_000 - anchoHz / 2); }
        private BufferedImage cascada;           // historia (una fila por línea)
        private float[] espectro;                 // última línea suavizada (dB)
        private volatile long centroHz = 0;       // frecuencia de la emisora (0 = desconocida)
        private long centroDibujado = Long.MIN_VALUE;
        private volatile double anchoHz = 60_000; // ancho visible total
        private volatile String modo = "";
        private volatile String aviso = "Pulsa el botón Cascada para empezar";
        private volatile String pie = "";
        private Point raton;
        java.util.function.LongConsumer alPinchar;
        java.util.function.IntConsumer alRueda;     // rueda: mover la frecuencia (como el dial)
        java.util.function.IntConsumer alZoom;      // Ctrl + rueda: acercar / alejar

        PanelCascada() {
            setOpaque(true);
            setBackground(new Color(8, 14, 26));
            setBorder(BorderFactory.createLineBorder(new Color(58, 74, 100)));
            setPreferredSize(new Dimension(400, altoTotal));   // el alto se cambia arrastrando el borde de abajo
            setMinimumSize(new Dimension(200, 172));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    raton = e.getPoint();
                    setCursor(Cursor.getPredefinedCursor(e.getY() >= getHeight() - 6 ? Cursor.S_RESIZE_CURSOR : Cursor.CROSSHAIR_CURSOR));
                    repaint();
                }
                @Override public void mousePressed(MouseEvent e) {
                    if (e.getY() < getHeight() - 6) {
                        if (SwingUtilities.isLeftMouseButton(e)) {
                            arrastrandoVista = true;
                            seMovioVista = false;
                            xInicioVista = e.getX();
                            vistaInicio = vistaHz;
                        }
                        return;
                    }
                    arrastrandoBorde = true;
                    yInicioArrastre = e.getYOnScreen();
                    altoInicioArrastre = getHeight();
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (arrastrandoVista) {
                        int dx = e.getX() - xInicioVista;
                        if (Math.abs(dx) >= 3) seMovioVista = true;
                        if (!seMovioVista) return;
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                        double lim = vistaMaxima();
                        vistaHz = Math.max(-lim, Math.min(lim, vistaInicio - dx * anchoHz / anchoUtil()));
                        raton = e.getPoint();
                        repaint();
                        return;
                    }
                    if (!arrastrandoBorde) return;
                    altoTotal = Math.max(130, Math.min(700, altoInicioArrastre + e.getYOnScreen() - yInicioArrastre));
                    setPreferredSize(new Dimension(getPreferredSize().width, altoTotal));
                    setMinimumSize(new Dimension(200, Math.min(altoTotal, 172)));
                    revalidate();
                    Container top = getTopLevelAncestor();
                    if (top != null) top.validate();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (arrastrandoVista) {
                        arrastrandoVista = false;
                        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                        return;
                    }
                    if (!arrastrandoBorde) return;
                    arrastrandoBorde = false;
                    ConfigApp.putInt("CASCADA_ALTO", altoTotal);
                }
                @Override public void mouseExited(MouseEvent e) { raton = null; repaint(); }
                @Override public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e) && centroHz > 0 && alDerecho != null) { alDerecho.accept(e, hzEnX(e.getX())); return; }
                    if (!SwingUtilities.isLeftMouseButton(e) || centroHz <= 0 || alPinchar == null) return;
                    if (seMovioVista) { seMovioVista = false; return; }   // ha sido un arrastre, no un clic
                    long hz = hzEnX(e.getX());
                    vistaHz = 0;   // al elegir una señal, la cascada se vuelve a centrar en la emisora
                    alPinchar.accept(Math.round(hz / 10.0) * 10);   // a 10 Hz
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener(e -> {
                int giro = -e.getWheelRotation();   // hacia arriba = subir
                if (giro == 0) return;
                if (e.isControlDown()) { if (alZoom != null) alZoom.accept(giro); }
                else if (alRueda != null) alRueda.accept(giro);
            });
            // La ayuda va escrita en el pie de la cascada (un recuadro emergente tapaba la cascada)
        }

        /** Botón derecho sobre la cascada (con la frecuencia que hay debajo del ratón). */
        java.util.function.BiConsumer<MouseEvent, Long> alDerecho;
        /** Momento del último cambio de frecuencia: las líneas que aún llegan con la frecuencia anterior no se pintan. */
        private volatile long cambioCentroNs = 0;
        void setCentro(long hz) {
            if (hz > 0 && centroHz > 0 && hz != centroHz) cambioCentroNs = System.nanoTime();
            centroHz = hz;
            repaint();
        }
        void setAncho(double hz) {
            anchoHz = hz;
            double lim = vistaMaxima();
            vistaHz = Math.max(-lim, Math.min(lim, vistaHz));   // al acercar/alejar, la vista no se sale de lo que se recibe
        }
        /** Al cambiar el ancho, lo pintado antes ya no vale: se empieza de nuevo. */
        void reiniciar() { cascada = null; espectro = null; repaint(); }
        void setModo(String m) { modo = m == null ? "" : m; }
        void setAviso(String t) { aviso = t; repaint(); }
        void setPie(String t) { pie = t == null ? "" : t; }
        double anchoHz() { return anchoHz; }
        int anchoUtil() { return Math.max(10, getWidth() - 2); }

        private long hzEnX(int x) {
            int w = anchoUtil();
            return centroHz + Math.round(vistaHz) + Math.round((x - 1 - w / 2.0) * anchoHz / w);
        }

        /** Nueva línea de potencias (dB), una por píxel. Se llama en el hilo de Swing. */
        void nuevaLinea(float[] db) {
            // Justo después de cambiar de frecuencia aún llegan líneas tomadas antes del cambio: se saltan para
            // que no se pinten descolocadas (desplazadas a la nueva frecuencia sin serlo)
            if (cambioCentroNs != 0 && System.nanoTime() - cambioCentroNs < 200_000_000L) return;
            int w = db.length, alto = Math.max(10, getHeight() - altoEsp() - ALTO_ESCALA - ALTO_PIE - 2);
            if (cascada == null || cascada.getWidth() != w || cascada.getHeight() != alto) {
                cascada = new BufferedImage(w, alto, BufferedImage.TYPE_INT_RGB);
                filaNueva = 0;
                Graphics2D g = cascada.createGraphics();
                g.setColor(new Color(5, 10, 40));
                g.fillRect(0, 0, w, alto);
                g.dispose();
                espectro = null;
                centroDibujado = centroHz + Math.round(vistaHz);
            }
            // Si la emisora ha cambiado de frecuencia (o se ha movido la vista), lo anterior se desplaza (como en HDSDR)
            long c = centroHz > 0 ? centroHz + Math.round(vistaHz) : 0;
            if (centroDibujado != Long.MIN_VALUE && c != centroDibujado && c > 0 && centroDibujado > 0) {
                int dx = (int) Math.round((c - centroDibujado) * w / anchoHz);
                if (Math.abs(dx) >= w) {
                    Graphics2D g = cascada.createGraphics();
                    g.setColor(new Color(5, 10, 40));
                    g.fillRect(0, 0, w, alto);
                    g.dispose();
                } else if (dx != 0) {
                    BufferedImage nueva = new BufferedImage(w, alto, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = nueva.createGraphics();
                    g.setColor(new Color(5, 10, 40));
                    g.fillRect(0, 0, w, alto);
                    g.drawImage(cascada, -dx, 0, null);
                    g.dispose();
                    cascada = nueva;
                }
                espectro = null;
            }
            centroDibujado = c;
            // Nivel automático: suelo de ruido (percentil 20, suavizado) y 45 dB por encima
            float[] orden = db.clone();
            java.util.Arrays.sort(orden);
            // Contraste 0 % = 45 dB de escala (suave) … 100 % = 15 dB (muy marcado). El ruido queda oscuro (3 dB por encima del suelo)
            float sueloAhora = orden[(int) (orden.length * 0.4)], rango = 45f - 0.3f * Math.max(0, Math.min(100, contraste));
            sueloSuave = Float.isNaN(sueloSuave) ? sueloAhora : sueloSuave * 0.9f + sueloAhora * 0.1f;
            float suelo = sueloSuave;
            // Búfer circular: la fila nueva se escribe encima de la más vieja (sin mover toda la imagen)
            filaNueva = (filaNueva - 1 + alto) % alto;
            int[] fila = new int[w];
            for (int i = 0; i < w; i++) fila[i] = color((db[i] - suelo - 3f) / rango);
            cascada.setRGB(0, filaNueva, w, 1, fila, 0, w);
            // Espectro suavizado
            if (espectro == null || espectro.length != w) espectro = db.clone();
            else for (int i = 0; i < w; i++) espectro[i] = espectro[i] * 0.6f + db[i] * 0.4f;
            sueloEspectro = suelo;
            aviso = null;
            repaint();
        }
        private float sueloEspectro = -100, sueloSuave = Float.NaN;
        /** Contraste de la cascada (0-100 %), de Configurar Cascada. */
        static volatile int contraste = Math.max(0, Math.min(100, ConfigApp.getInt("CASCADA_CONTRASTE", 70)));
        private int filaNueva = 0;   // fila del búfer circular donde está la línea más reciente

        private static int color(float v) {
            v = Math.max(0, Math.min(1, v));
            // Como HDSDR: ruido azul oscuro; las señales pasan a celeste, amarillo y blanco según su fuerza
            float[][] p = {{0f, 4, 10, 60}, {0.2f, 15, 50, 175}, {0.4f, 40, 140, 235}, {0.6f, 245, 225, 70}, {0.8f, 255, 250, 185}, {1f, 255, 255, 255}};
            for (int k = 0; k < p.length - 1; k++) {
                if (v <= p[k + 1][0]) {
                    float t = (v - p[k][0]) / (p[k + 1][0] - p[k][0]);
                    int r = (int) (p[k][1] + t * (p[k + 1][1] - p[k][1]));
                    int g = (int) (p[k][2] + t * (p[k + 1][2] - p[k][2]));
                    int b = (int) (p[k][3] + t * (p[k + 1][3] - p[k][3]));
                    return (r << 16) | (g << 8) | b;
                }
            }
            return 0xF02814;
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = anchoUtil(), x0 = 1, y0 = 1;
            int ySp = y0, yEsc = y0 + altoEsp(), yCas = yEsc + ALTO_ESCALA;
            int altoCas = Math.max(10, getHeight() - altoEsp() - ALTO_ESCALA - ALTO_PIE - 2);
            int yPie = yCas + altoCas;
            // Espectro
            g.setColor(new Color(30, 42, 62));
            for (int k = 1; k < 4; k++) g.drawLine(x0, ySp + k * altoEsp() / 4, x0 + w, ySp + k * altoEsp() / 4);
            if (espectro != null && espectro.length == w) {
                java.awt.geom.Path2D.Float linea = new java.awt.geom.Path2D.Float();
                java.awt.geom.Path2D.Float relleno = new java.awt.geom.Path2D.Float();
                relleno.moveTo(x0, ySp + altoEsp());
                for (int i = 0; i < w; i++) {
                    float v = Math.max(0, Math.min(1, (espectro[i] - sueloEspectro + 5) / 55f));
                    float y = ySp + altoEsp() - v * (altoEsp() - 2);
                    if (i == 0) linea.moveTo(x0 + i, y); else linea.lineTo(x0 + i, y);
                    relleno.lineTo(x0 + i, y);
                }
                relleno.lineTo(x0 + w, ySp + altoEsp());
                relleno.closePath();
                g.setColor(new Color(20, 90, 40));
                g.fill(relleno);
                g.setColor(new Color(80, 230, 80));
                g.draw(linea);
            }
            // Escala
            g.setColor(new Color(22, 32, 48));
            g.fillRect(x0, yEsc, w, ALTO_ESCALA);
            g.setFont(new Font("Arial", Font.PLAIN, 9));
            FontMetrics fm = g.getFontMetrics();
            double pxPorHz = w / anchoHz;
            double paso = anchoHz > 80_000 ? 10_000 : anchoHz > 30_000 ? 5_000 : 2_000;
            double vista = vistaHz;
            long c = centroHz > 0 ? centroHz + Math.round(vista) : 0;
            double ini = c > 0 ? Math.ceil((c - anchoHz / 2) / paso) * paso : Math.ceil(-anchoHz / 2 / paso) * paso;
            double fin = c > 0 ? c + anchoHz / 2 : anchoHz / 2;
            for (double f = ini; f <= fin; f += paso) {
                int x = (int) Math.round(x0 + w / 2.0 + (f - (c > 0 ? c : 0)) * pxPorHz);
                g.setColor(new Color(143, 166, 200));
                g.drawLine(x, yEsc, x, yEsc + 3);
                String t = c > 0 ? String.format(java.util.Locale.GERMANY, "%,.0f", f / 1000.0)
                        : String.format("%+.0f kHz", f / 1000.0);
                int tw = fm.stringWidth(t);
                if (x - tw / 2 > x0 + 2 && x + tw / 2 < x0 + w - 2) {
                    g.setColor(new Color(200, 215, 235));
                    g.drawString(t, x - tw / 2, yEsc + ALTO_ESCALA - 3);
                }
            }
            // Cascada
            if (cascada != null) {
                // La más reciente arriba: primero desde filaNueva hasta el final y luego el principio
                int alto = cascada.getHeight(), cw = cascada.getWidth(), parte1 = alto - filaNueva;
                double esc = (double) altoCas / alto;
                int y1 = yCas + (int) Math.round(parte1 * esc);
                g.drawImage(cascada, x0, yCas, x0 + w, y1, 0, filaNueva, cw, alto, null);
                if (filaNueva > 0) g.drawImage(cascada, x0, y1, x0 + w, yCas + altoCas, 0, 0, cw, filaNueva, null);
            }
            else { g.setColor(new Color(5, 10, 40)); g.fillRect(x0, yCas, w, altoCas); }
            // Zona del filtro (según el modo) y línea de la frecuencia sintonizada
            int[] pb = bandaPasante(modo);
            int xc = (int) Math.round(x0 + w / 2.0 - vista * pxPorHz);   // la frecuencia de la emisora (puede quedar fuera)
            Shape recorteAntes = g.getClip();
            g.clipRect(x0, ySp, w, yCas + altoCas - ySp);
            g.setColor(new Color(80, 140, 255, 50));
            int xa = (int) Math.round(xc + pb[0] * pxPorHz), xb = (int) Math.round(xc + pb[1] * pxPorHz);
            g.fillRect(Math.min(xa, xb), ySp, Math.max(2, Math.abs(xb - xa)), altoEsp());
            g.fillRect(Math.min(xa, xb), yCas, Math.max(2, Math.abs(xb - xa)), altoCas);
            g.setColor(new Color(230, 40, 40));
            g.drawLine(xc, ySp, xc, ySp + altoEsp());
            g.drawLine(xc, yCas, xc, yCas + altoCas);
            g.setClip(recorteAntes);
            // Si la frecuencia de la emisora queda fuera de la vista, una flecha indica hacia dónde está
            if (Math.abs(vista) >= 1 && (xc < x0 || xc > x0 + w)) {
                boolean izq = xc < x0;
                String t = izq ? "◄ emisora" : "emisora ►";
                g.setFont(new Font("Dialog", Font.BOLD, 11));
                int tw = g.getFontMetrics().stringWidth(t);
                int tx = izq ? x0 + 6 : x0 + w - tw - 6;
                g.setColor(new Color(0, 0, 0, 170));
                g.fillRoundRect(tx - 4, yCas + 4, tw + 8, 16, 6, 6);
                g.setColor(new Color(255, 90, 90));
                g.drawString(t, tx, yCas + 16);
            }
            // Ratón: frecuencia bajo el puntero
            if (raton != null && c > 0) {
                g.setColor(new Color(255, 255, 255, 120));
                g.drawLine(raton.x, ySp, raton.x, yCas + altoCas);
                String t = String.format(java.util.Locale.GERMANY, "%,.2f kHz", hzEnX(raton.x) / 1000.0);
                g.setFont(new Font("Arial", Font.BOLD, 11));
                int tw = g.getFontMetrics().stringWidth(t);
                int tx = Math.min(raton.x + 8, x0 + w - tw - 8);
                g.setColor(new Color(0, 0, 0, 170));
                g.fillRoundRect(tx - 4, ySp + 2, tw + 8, 15, 6, 6);
                g.setColor(new Color(240, 190, 60));
                g.drawString(t, tx, ySp + 13);
            }
            // Pie
            g.setColor(new Color(22, 32, 48));
            g.fillRect(x0, yPie, w, ALTO_PIE);
            g.setFont(new Font("Arial", Font.PLAIN, 9));
            g.setColor(new Color(170, 185, 210));
            g.drawString(pie, x0 + 5, yPie + ALTO_PIE - 3);
            String der = "Pincha en una señal para ir a ella · Arrastra: mover la vista · Rueda: mover · Ctrl + rueda: acercar/alejar"
                    + " · Botón derecho: calibrar o centrar · Arrastra el borde de abajo: más alta";
            int libre = w - g.getFontMetrics().stringWidth(pie) - 30;
            if (g.getFontMetrics().stringWidth(der) > libre)
                der = "Pincha: ir a la señal · Arrastra: mover · Rueda: mover · Ctrl+rueda: zoom · Botón dcho.: calibrar · Borde de abajo: más alta";
            g.drawString(der, x0 + w - g.getFontMetrics().stringWidth(der) - 6, yPie + ALTO_PIE - 3);
            // Aviso (sin datos)
            if (aviso != null) {
                g.setFont(new Font("Arial", Font.BOLD, 13));
                int tw = g.getFontMetrics().stringWidth(aviso);
                g.setColor(new Color(0, 0, 0, 160));
                g.fillRoundRect(x0 + w / 2 - tw / 2 - 10, yCas + altoCas / 2 - 14, tw + 20, 24, 8, 8);
                g.setColor(new Color(240, 190, 60));
                g.drawString(aviso, x0 + w / 2 - tw / 2, yCas + altoCas / 2 + 3);
            }
            g.dispose();
        }

        /** Hz (desde la portadora) que deja pasar el filtro en cada modo, para sombrearlos. */
        static int[] bandaPasante(String modo) {
            switch (modo == null ? "" : modo) {
                case "USB": return new int[]{300, 2700};
                case "LSB": return new int[]{-2700, -300};
                case "SSB": return new int[]{-2700, 2700};
                case "CW": return new int[]{-250, 250};
                case "AM": return new int[]{-3000, 3000};
                case "FM": return new int[]{-6000, 6000};
                default: return new int[]{-1500, 1500};
            }
        }
    }

    /**
     * Lector del RTL-SDR a través de rtl_tcp (protocolo sencillo por TCP): fija la FI, recibe las muestras I/Q,
     * calcula la FFT y entrega una línea de potencias por píxel. El RTL-SDR se sintoniza un poco por encima de la FI
     * para que su "pico" del centro (DC) no caiga sobre la frecuencia de la emisora.
     */
    static class LectorRtlTcp {
        // El RTL-SDR muestrea a 2,4 MHz (así rtl_tcp entrega la señal cada ~55 ms y la cascada va fluida);
        // aquí se filtra y se reduce a 240 kHz, que es lo que hace falta para ver ±40 kHz.
        static final int MUESTREO_RTL = 2_400_000, DIEZMADO = 10, MUESTREO = MUESTREO_RTL / DIEZMADO;
        static final int N = 16384, SALTO = 4096;   // más detalle (15 Hz) y una FFT cada 4096 muestras: ~59 por segundo
        static volatile int mediaCascada = Math.max(1, ConfigApp.getInt("CASCADA_MEDIA", 2));
        static final int DESPLAZAMIENTO = 48_000;   // Hz por encima de la FI
        /** Ganancia si no se ha elegido otra: fija, 29,7 dB (parecida a la que usa HDSDR). */
        static final int GANANCIA_CASCADA_INICIAL = 297;
        /** Corrección (Hz) del sitio de la portadora dentro de la FI para el modo actual (calibración de la cascada). */
        static volatile int ajusteHz = 0;
        /** Veces que la cascada iba con retraso y se ha puesto al día (para el registro). */
        static volatile int retrasosRecuperados = 0;
        interface Receptor { void linea(float[] db); void estado(String texto); }

        private volatile boolean activo = false;
        private Thread hilo;
        private java.net.Socket socket;
        private Process proceso;
        private final java.util.function.IntSupplier anchoPx;
        private final java.util.function.DoubleSupplier anchoHz;
        private final Receptor receptor;

        LectorRtlTcp(java.util.function.IntSupplier anchoPx, java.util.function.DoubleSupplier anchoHz, Receptor receptor) {
            this.anchoPx = anchoPx;
            this.anchoHz = anchoHz;
            this.receptor = receptor;
        }

        boolean activo() { return activo; }

        void arrancar() {
            if (activo) return;
            activo = true;
            hilo = new Thread(this::bucle, "CascadaRTL");
            hilo.setDaemon(true);
            hilo.start();
        }

        void parar() {
            activo = false;
            try { if (socket != null) socket.close(); } catch (Exception ignored) { }
            pararProceso();
        }

        private void pararProceso() {
            Process p = proceso;
            proceso = null;
            if (p != null) {
                p.destroy();
                try { if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly(); } catch (InterruptedException ignored) { }
            }
        }

        /** ¿Es el programa rtl_tcp? (rtl_tcp.exe; se admite también un nombre como rtl_tcp64.exe) */
        static boolean esRtlTcp(File f) {
            String n = f.getName().toLowerCase();
            return n.startsWith("rtl_tcp") && n.endsWith(".exe");
        }

        /** Dónde está rtl_tcp.exe: la ruta configurada, o junto al programa. */
        static File buscarRtlTcp() {
            String ruta = ConfigApp.get("RTL_TCP_RUTA", "").trim();
            // Solo vale si de verdad es rtl_tcp (si se eligió por error HDSDR u otro programa, no se usa)
            if (!ruta.isEmpty() && new File(ruta).isFile() && esRtlTcp(new File(ruta))) return new File(ruta);
            java.util.List<File> sitios = new ArrayList<>();
            File dir = new File(System.getProperty("user.dir"));
            sitios.add(new File(dir, "rtl_tcp.exe"));
            sitios.add(new File(dir, "rtl-sdr" + File.separator + "rtl_tcp.exe"));
            sitios.add(new File(dir, "rtl-sdr-blog" + File.separator + "rtl_tcp.exe"));
            try {
                File jar = new File(LibroGuardiaUI.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                File d = jar.isFile() ? jar.getParentFile() : jar;
                for (int k = 0; k < 3 && d != null; k++, d = d.getParentFile()) {
                    sitios.add(new File(d, "rtl_tcp.exe"));
                    sitios.add(new File(d, "rtl-sdr" + File.separator + "rtl_tcp.exe"));
                }
            } catch (Exception ignored) { }
            for (File f : sitios) if (f.isFile()) return f;
            return null;
        }

        private java.net.Socket conectar(int intentos) {
            for (int i = 0; i < intentos && activo; i++) {
                try {
                    java.net.Socket s = new java.net.Socket();
                    s.connect(new java.net.InetSocketAddress("127.0.0.1", 1234), 800);
                    return s;
                } catch (Exception ex) {
                    if (proceso != null && !proceso.isAlive()) return null;
                    try { Thread.sleep(400); } catch (InterruptedException ignored) { return null; }
                }
            }
            return null;
        }

        private static void orden(java.io.OutputStream out, int cmd, int valor) throws java.io.IOException {
            out.write(new byte[]{(byte) cmd, (byte) (valor >>> 24), (byte) (valor >>> 16), (byte) (valor >>> 8), (byte) valor});
            out.flush();
        }

        private void bucle() {
            File registro = new File(System.getProperty("user.dir"), "rtl_tcp_registro.txt");
            try {
                long fi = Long.parseLong(ConfigApp.get("CASCADA_FI", "8830000").trim());
                boolean invertir = "1".equals(ConfigApp.get("CASCADA_INVERTIR", "1"));
                int ganancia = ConfigApp.getInt("CASCADA_GANANCIA", GANANCIA_CASCADA_INICIAL);   // décimas de dB; -1 = automática
                int ppm = ConfigApp.getInt("CASCADA_PPM", 0);
                // Invertida: se sintoniza por debajo de la FI y se le da la vuelta; así las cuentas son las mismas
                long sintonia = invertir ? fi - DESPLAZAMIENTO : fi + DESPLAZAMIENTO;
                receptor.estado("Conectando con el RTL-SDR…");
                socket = conectar(1);   // ¿ya hay un rtl_tcp funcionando?
                if (socket == null) {
                    File exe = buscarRtlTcp();
                    if (exe == null) {
                        receptor.estado("No encuentro rtl_tcp.exe: tiene que estar en la carpeta rtl-sdr, junto a Bitacora 26.exe");
                        activo = false;
                        return;
                    }
                    ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(), "-a", "127.0.0.1", "-p", "1234",
                            "-s", String.valueOf(MUESTREO_RTL), "-f", String.valueOf(sintonia));
                    pb.directory(exe.getParentFile());
                    pb.redirectErrorStream(true);
                    pb.redirectOutput(registro);
                    proceso = pb.start();
                    socket = conectar(15);
                }
                if (socket == null) {
                    String motivo = "No se pudo abrir el RTL-SDR";
                    try {
                        String t = new String(java.nio.file.Files.readAllBytes(registro.toPath()), StandardCharsets.UTF_8).toLowerCase();
                        if (t.contains("usb_claim_interface") || t.contains("usb_open error") || t.contains("busy"))
                            motivo = "El RTL-SDR está ocupado: cierra HDSDR u otro programa que lo use";
                        else if (t.contains("no supported devices")) motivo = "No encuentro el RTL-SDR: ¿está enchufado?";
                    } catch (Exception ignored) { }
                    receptor.estado(motivo);
                    activo = false;
                    pararProceso();
                    return;
                }
                socket.setReceiveBufferSize(1 << 20);
                java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(socket.getInputStream(), 1 << 16));
                java.io.OutputStream out = socket.getOutputStream();
                byte[] cabecera = new byte[12];
                in.readFully(cabecera);   // "RTL0" + sintonizador + número de ganancias
                orden(out, 0x02, MUESTREO_RTL);
                orden(out, 0x01, (int) sintonia);
                if (ppm != 0) orden(out, 0x05, ppm);
                // Automática: la del sintonizador. El control automático del chip (0x08) se deja apagado: baja mucho las señales
                if (ganancia < 0) { orden(out, 0x03, 0); orden(out, 0x08, 0); }
                else { orden(out, 0x03, 1); orden(out, 0x08, 0); orden(out, 0x04, ganancia); }
                receptor.estado("Esperando señal…");

                double[] ventana = new double[N];
                for (int i = 0; i < N; i++) ventana[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
                // Filtro paso bajo (para quedarse con ±100 kHz antes de reducir la velocidad 10 veces)
                int taps = 96;
                double[] coef = new double[taps];
                double corte = 100_000.0 / MUESTREO_RTL, sumaC = 0;
                for (int k = 0; k < taps; k++) {
                    double m = k - (taps - 1) / 2.0;
                    double sinc = m == 0 ? 2 * corte : Math.sin(2 * Math.PI * corte * m) / (Math.PI * m);
                    coef[k] = sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * k / (taps - 1)));
                    sumaC += coef[k];
                }
                for (int k = 0; k < taps; k++) coef[k] /= sumaC;
                // Se corre la señal DESPLAZAMIENTO Hz para que la frecuencia de la emisora quede en el centro
                int periodo = MUESTREO_RTL / DESPLAZAMIENTO;   // 50 muestras exactas
                double[] cosT = new double[periodo], senT = new double[periodo];
                for (int k = 0; k < periodo; k++) {
                    cosT[k] = Math.cos(2 * Math.PI * k / periodo);
                    senT[k] = Math.sin(2 * Math.PI * k / periodo);
                }
                double[] histI = new double[taps * 2], histQ = new double[taps * 2];   // historia (doble, para no dar la vuelta)
                int posH = 0, fase = 0, cuenta = 0;
                double[] colaRe = new double[N], colaIm = new double[N];   // últimas N muestras ya reducidas (circular)
                int posC = 0, nuevas = 0;
                byte[] bloque = new byte[2 * DIEZMADO * 512];
                double[] re = new double[N], im = new double[N], acum = new double[N];
                int sumadas = 0;
                while (activo) {
                    // Leer y reducir hasta tener SALTO muestras nuevas
                    while (nuevas < SALTO && activo) {
                        // Si el ordenador va justo y se acumulan datos (más de ~0,1 s), se tiran los viejos:
                        // así la cascada siempre muestra lo de ahora y no se queda atrás respecto a la escala
                        int pendiente = in.available();
                        if (pendiente > 512 * 1024) {
                            long tirar = pendiente - bloque.length;
                            tirar -= tirar % 2;   // sin partir una pareja I/Q
                            while (tirar > 0) {
                                long t = in.skip(tirar);
                                if (t <= 0) break;
                                tirar -= t;
                            }
                            retrasosRecuperados++;
                        }
                        in.readFully(bloque);
                        for (int p = 0; p < bloque.length; p += 2) {
                            double a = ((bloque[p] & 0xFF) - 127.5) / 127.5, b = ((bloque[p + 1] & 0xFF) - 127.5) / 127.5;
                            if (invertir) b = -b;
                            double c = cosT[fase], sn = senT[fase];
                            if (++fase == periodo) fase = 0;
                            double si = a * c - b * sn, sq = a * sn + b * c;   // multiplicar por e^(+j·2π·desplazamiento·t)
                            histI[posH] = si; histI[posH + taps] = si;
                            histQ[posH] = sq; histQ[posH + taps] = sq;
                            if (++posH == taps) posH = 0;
                            if (++cuenta < DIEZMADO) continue;
                            cuenta = 0;
                            double oi = 0, oq = 0;
                            for (int k = 0; k < taps; k++) { oi += coef[k] * histI[posH + k]; oq += coef[k] * histQ[posH + k]; }
                            colaRe[posC] = oi;
                            colaIm[posC] = oq;
                            if (++posC == N) posC = 0;
                            nuevas++;
                        }
                    }
                    nuevas = 0;
                    for (int i = 0; i < N; i++) {   // de la más vieja (posC) a la más nueva
                        int k = posC + i; if (k >= N) k -= N;
                        re[i] = colaRe[k] * ventana[i];
                        im[i] = colaIm[k] * ventana[i];
                    }
                    fft(re, im);
                    for (int i = 0; i < N; i++) acum[i] += re[i] * re[i] + im[i] * im[i];
                    // Velocidad: 1 = rápida (~59 líneas/s), 2 = normal (~29), 4 = lenta (~15)
                    int media = Math.max(1, mediaCascada);
                    if (++sumadas < media) continue;
                    // Píxeles: cada uno es un trozo de frecuencia alrededor de la de la emisora
                    int w = Math.max(10, anchoPx.getAsInt());
                    double ancho = anchoHz.getAsDouble();
                    float[] db = new float[w];
                    double hzBin = (double) MUESTREO / N;
                    for (int x = 0; x < w; x++) {
                        int aj = ajusteHz;
                        double vis = PanelCascada.vistaHz;
                        double d0 = -ancho / 2 + ancho * x / w + aj + vis, d1 = -ancho / 2 + ancho * (x + 1) / w + aj + vis;
                        // la señal ya está centrada: el bin k es k·hzBin Hz desde la portadora
                        int k0 = (int) Math.floor(d0 / hzBin), k1 = (int) Math.ceil(d1 / hzBin);
                        double maximo = 1e-20;
                        for (int k = k0; k <= Math.max(k0, k1 - 1); k++) {
                            int idx = ((k % N) + N) % N;   // FFT sin reordenar: negativos al final
                            maximo = Math.max(maximo, acum[idx]);
                        }
                        db[x] = (float) (10 * Math.log10(maximo / sumadas));
                    }
                    java.util.Arrays.fill(acum, 0);
                    sumadas = 0;
                    receptor.linea(db);
                }
            } catch (java.io.EOFException | java.net.SocketException ex) {
                if (activo) receptor.estado("Se ha perdido la conexión con el RTL-SDR");
            } catch (Exception ex) {
                if (activo) receptor.estado("Error de la cascada: " + ex.getMessage());
            } finally {
                try { if (socket != null) socket.close(); } catch (Exception ignored) { }
                pararProceso();
                activo = false;
            }
        }

        /** FFT compleja (radix 2, en el sitio). */
        static void fft(double[] re, double[] im) {
            int n = re.length;
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) j ^= bit;
                j ^= bit;
                if (i < j) {
                    double t = re[i]; re[i] = re[j]; re[j] = t;
                    t = im[i]; im[i] = im[j]; im[j] = t;
                }
            }
            for (int len = 2; len <= n; len <<= 1) {
                double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
                for (int i = 0; i < n; i += len) {
                    double cr = 1, ci = 0;
                    for (int k = 0; k < len / 2; k++) {
                        int a = i + k, b = i + k + len / 2;
                        double tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
                        re[b] = re[a] - tr; im[b] = im[a] - ti;
                        re[a] += tr; im[a] += ti;
                        double nr = cr * wr - ci * wi;
                        ci = cr * wi + ci * wr;
                        cr = nr;
                    }
                }
            }
        }
    }

    /** Teselas de OpenStreetMap para la pestaña MAPA (las últimas 400 descargadas se guardan en memoria). */
    static class TeselasOsm {
        private static final Map<String, BufferedImage> cache = new LinkedHashMap<>(256, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 400; }
        };
        private static final java.util.Set<String> pidiendo = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private static final java.util.concurrent.ExecutorService descargas = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "TeselasMapaQso");
            t.setDaemon(true);
            return t;
        });
        private static final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(6)).build();

        /** La tesela si ya está; si no, se pide y, cuando llega, se vuelve a pintar "quien". */
        static BufferedImage tesela(int z, int x, int y, Component quien) {
            String clave = z + "/" + x + "/" + y;
            synchronized (cache) {
                BufferedImage img = cache.get(clave);
                if (img != null) return img;
            }
            if (pidiendo.add(clave)) {
                descargas.submit(() -> {
                    try {
                        HttpRequest req = HttpRequest.newBuilder(URI.create(urlTeselasOsm() + clave + ".png"))
                                .timeout(java.time.Duration.ofSeconds(10))
                                .header("User-Agent", AGENTE_OSM).GET().build();
                        HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                        if (r.statusCode() == 200) {
                            BufferedImage im = ImageIO.read(new java.io.ByteArrayInputStream(r.body()));
                            if (im != null) {
                                synchronized (cache) { cache.put(clave, im); }
                                SwingUtilities.invokeLater(quien::repaint);
                            }
                        }
                    } catch (Exception ignored) {
                        // sin internet: se queda el contorno del mundo
                    } finally {
                        pidiendo.remove(clave);
                    }
                });
            }
            return null;
        }
    }

    static class MapaQso extends JPanel {
        private static final double RADIO_TIERRA_KM = 6371.0;
        private static java.awt.geom.Path2D.Float tierra;     // en grados: x = lon, y = -lat
        private static java.awt.geom.Path2D.Float fronteras;
        private static boolean mapaCargado = false;

        private double[] casa;      // {lat, lon} mi QTH
        private double[] remoto;    // {lat, lon} el corresponsal
        private String nombreCasa = "";
        private String nombreRemoto = "";
        private String aviso = "";

        // Vista: mapa de OpenStreetMap (el mismo que el de elegir Grid). Mientras no se toque con el ratón,
        // la vista se calcula sola para que se vea la ruta entera.
        private int zoom = 2;                  // nivel de zoom de OpenStreetMap (1..17)
        private double cx, cy;                 // centro, en píxeles del mundo a ese zoom
        private boolean vistaManual = false;
        private java.awt.Point arrastreDesde;

        MapaQso() {
            setPreferredSize(new Dimension(435, 285));
            setBorder(BorderFactory.createLineBorder(new Color(120, 120, 120)));
            setToolTipText("<html>Rueda del ratón: acercar / alejar<br>Arrastrar: mover el mapa<br>Doble clic: volver a ver la ruta</html>");
            cargarMapaSiHaceFalta();

            MouseAdapter raton = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        arrastreDesde = e.getPoint();
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    arrastreDesde = null;
                    setCursor(Cursor.getDefaultCursor());
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (arrastreDesde == null) return;
                    cx -= e.getX() - arrastreDesde.x;
                    cy -= e.getY() - arrastreDesde.y;
                    arrastreDesde = e.getPoint();
                    vistaManual = true;
                    repaint();
                }

                @Override
                public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                    int nuevo = Math.max(1, Math.min(17, zoom + (e.getWheelRotation() < 0 ? 1 : -1)));
                    if (nuevo == zoom) return;
                    // El punto que está bajo el ratón se queda quieto
                    double s = tam(zoom);
                    double px = cx + (e.getX() - getWidth() / 2.0), py = cy + (e.getY() - getHeight() / 2.0);
                    double f = tam(nuevo) / s;
                    zoom = nuevo;
                    cx = px * f - (e.getX() - getWidth() / 2.0);
                    cy = py * f - (e.getY() - getHeight() / 2.0);
                    vistaManual = true;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        vistaManual = false;   // volver a la vista automática de la ruta
                        repaint();
                    }
                }
            };
            addMouseListener(raton);
            addMouseMotionListener(raton);
            addMouseWheelListener(raton);
        }

        private static double tam(int z) { return 256.0 * (1 << z); }

        /** Píxel del mundo (a un zoom) de una posición. La longitud no se "da la vuelta" (sirve para rutas seguidas). */
        private static double[] pixelMundo(double lat, double lon, int z) {
            double s = tam(z);
            double la = Math.toRadians(Math.max(-85, Math.min(85, lat)));
            return new double[]{(lon + 180) / 360 * s, (1 - Math.log(Math.tan(la) + 1 / Math.cos(la)) / Math.PI) / 2 * s};
        }

        /** Calcula zoom y centro para ver la ruta (o mi QTH, o el mundo). */
        private void vistaAutomatica(List<double[]> camino, int w, int h) {
            if (camino == null && casa == null) {
                zoom = w > 700 ? 2 : 1;
                double[] p = pixelMundo(30, 0, zoom);
                cx = p[0];
                cy = p[1];
                return;
            }
            if (camino == null) {
                zoom = 6;
                double[] p = pixelMundo(casa[0], casa[1], zoom);
                cx = p[0];
                cy = p[1];
                return;
            }
            for (int z = 12; z >= 1; z--) {
                double minX = 1e18, maxX = -1e18, minY = 1e18, maxY = -1e18;
                for (double[] q : camino) {
                    double[] p = pixelMundo(q[0], q[1], z);
                    minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
                }
                if ((maxX - minX) * 1.3 + 60 <= w && (maxY - minY) * 1.3 + 60 <= h || z == 1) {
                    zoom = z;
                    cx = (minX + maxX) / 2;
                    cy = (minY + maxY) / 2;
                    return;
                }
            }
        }

        /** Carga los contornos del mundo (recurso mapa_mundo.dat.gz) una sola vez. */
        private static synchronized void cargarMapaSiHaceFalta() {
            if (mapaCargado) return;
            mapaCargado = true;
            tierra = new java.awt.geom.Path2D.Float(java.awt.geom.Path2D.WIND_EVEN_ODD);
            fronteras = new java.awt.geom.Path2D.Float();
            try (java.io.InputStream is = LibroGuardiaUI.class.getResourceAsStream("/mapa_mundo.dat.gz")) {
                if (is == null) return;
                BufferedReader br = new BufferedReader(new java.io.InputStreamReader(
                        new java.util.zip.GZIPInputStream(is), StandardCharsets.US_ASCII));
                String linea;
                while ((linea = br.readLine()) != null) {
                    if (linea.length() < 3) continue;
                    char tipo = linea.charAt(0);
                    java.awt.geom.Path2D.Float destino = (tipo == 'T') ? tierra : fronteras;
                    String[] pts = linea.substring(2).split(" ");
                    for (int i = 0; i < pts.length; i++) {
                        int coma = pts[i].indexOf(',');
                        if (coma < 0) continue;
                        float lon = Integer.parseInt(pts[i].substring(0, coma)) / 100f;
                        float lat = Integer.parseInt(pts[i].substring(coma + 1)) / 100f;
                        if (i == 0) destino.moveTo(lon, -lat); else destino.lineTo(lon, -lat);
                    }
                    if (tipo == 'T') destino.closePath();
                }
            } catch (Exception e) {
                System.err.println("No se pudo cargar el mapa: " + e.getMessage());
            }
        }

        void setCasa(double[] latLon, String nombre) {
            vistaManual = false;   // nuevo contacto: se vuelve a la vista automática
            casa = latLon;
            nombreCasa = nombre == null ? "" : nombre;
            repaint();
        }

        void setRemoto(double[] latLon, String nombre, String avisoTexto) {
            vistaManual = false;
            remoto = latLon;
            nombreRemoto = nombre == null ? "" : nombre;
            aviso = avisoTexto == null ? "" : avisoTexto;
            repaint();
        }

        static double distanciaKm(double[] a, double[] b) {
            double f1 = Math.toRadians(a[0]), f2 = Math.toRadians(b[0]);
            double df = f2 - f1, dl = Math.toRadians(b[1] - a[1]);
            double h = Math.sin(df / 2) * Math.sin(df / 2) + Math.cos(f1) * Math.cos(f2) * Math.sin(dl / 2) * Math.sin(dl / 2);
            return 2 * RADIO_TIERRA_KM * Math.asin(Math.min(1, Math.sqrt(h)));
        }

        static double rumboGrados(double[] a, double[] b) {
            double f1 = Math.toRadians(a[0]), f2 = Math.toRadians(b[0]);
            double dl = Math.toRadians(b[1] - a[1]);
            double y = Math.sin(dl) * Math.cos(f2);
            double x = Math.cos(f1) * Math.sin(f2) - Math.sin(f1) * Math.cos(f2) * Math.cos(dl);
            return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
        }

        /** Puntos intermedios de la ruta más corta (círculo máximo), con longitudes "continuas". */
        private static List<double[]> ruta(double[] a, double[] b) {
            List<double[]> lista = new ArrayList<>();
            double f1 = Math.toRadians(a[0]), l1 = Math.toRadians(a[1]);
            double f2 = Math.toRadians(b[0]), l2 = Math.toRadians(b[1]);
            double d = distanciaKm(a, b) / RADIO_TIERRA_KM;
            int n = 64;
            double lonPrev = a[1];
            for (int i = 0; i <= n; i++) {
                double t = (double) i / n;
                double lat, lon;
                if (d < 1e-9) {
                    lat = a[0];
                    lon = a[1];
                } else {
                    double A = Math.sin((1 - t) * d) / Math.sin(d), B = Math.sin(t * d) / Math.sin(d);
                    double x = A * Math.cos(f1) * Math.cos(l1) + B * Math.cos(f2) * Math.cos(l2);
                    double y = A * Math.cos(f1) * Math.sin(l1) + B * Math.cos(f2) * Math.sin(l2);
                    double z = A * Math.sin(f1) + B * Math.sin(f2);
                    lat = Math.toDegrees(Math.atan2(z, Math.sqrt(x * x + y * y)));
                    lon = Math.toDegrees(Math.atan2(y, x));
                }
                while (lon - lonPrev > 180) lon -= 360;
                while (lon - lonPrev < -180) lon += 360;
                lonPrev = lon;
                lista.add(new double[]{lat, lon});
            }
            return lista;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();

            List<double[]> camino = (casa != null && remoto != null) ? ruta(casa, remoto) : null;
            if (!vistaManual) vistaAutomatica(camino, w, h);
            // Sin salirse por arriba ni por abajo del mapa; a los lados el mundo da la vuelta
            double s = tam(zoom);
            cy = s <= h ? s / 2 : Math.max(h / 2.0, Math.min(s - h / 2.0, cy));
            final double ox = w / 2.0 - cx, oy = h / 2.0 - cy;   // pantalla del píxel (0,0) del mundo
            int n = 1 << zoom;

            // --- Mar y contorno de la tierra (se ve aunque no haya internet) ---
            g.setColor(new Color(28, 74, 128));
            g.fillRect(0, 0, w, h);
            if (tierra != null) {
                java.awt.geom.Path2D.Double t = new java.awt.geom.Path2D.Double(java.awt.geom.Path2D.WIND_EVEN_ODD);
                double[] c = new double[6];
                for (java.awt.geom.PathIterator it = tierra.getPathIterator(null); !it.isDone(); it.next()) {
                    int tipo = it.currentSegment(c);
                    if (tipo == java.awt.geom.PathIterator.SEG_CLOSE) { t.closePath(); continue; }
                    double[] p = pixelMundo(-c[1], c[0], zoom);
                    if (tipo == java.awt.geom.PathIterator.SEG_MOVETO) t.moveTo(p[0], p[1]); else t.lineTo(p[0], p[1]);
                }
                double base = Math.floor(-ox / s) * s;
                for (int copia = -1; copia <= 2; copia++) {
                    Shape sh = java.awt.geom.AffineTransform.getTranslateInstance(ox + base + copia * s, oy).createTransformedShape(t);
                    if (!sh.getBounds2D().intersects(0, 0, w, h)) continue;
                    g.setColor(new Color(118, 150, 96));
                    g.fill(sh);
                }
            }

            // --- Teselas de OpenStreetMap (ciudades, carreteras...) ---
            int tx0 = (int) Math.floor((cx - w / 2.0) / 256), tx1 = (int) Math.floor((cx + w / 2.0) / 256);
            int ty0 = Math.max(0, (int) Math.floor((cy - h / 2.0) / 256)), ty1 = Math.min(n - 1, (int) Math.floor((cy + h / 2.0) / 256));
            for (int tx = tx0; tx <= tx1; tx++) {
                for (int ty = ty0; ty <= ty1; ty++) {
                    BufferedImage im = TeselasOsm.tesela(zoom, ((tx % n) + n) % n, ty, this);
                    if (im != null) g.drawImage(im, (int) Math.round(ox + tx * 256.0), (int) Math.round(oy + ty * 256.0), 256, 256, null);
                }
            }

            // --- Posición en pantalla (se elige la copia del mundo más cercana al centro) ---
            java.util.function.BiFunction<Double, Double, java.awt.geom.Point2D> proy = (lat, lon) -> {
                double[] p = pixelMundo(lat, lon, zoom);
                double dx = p[0] - cx;
                dx -= Math.round(dx / s) * s;
                return new java.awt.geom.Point2D.Double(w / 2.0 + dx, oy + p[1]);
            };

            // --- Ruta, puntos y datos ---
            if (camino != null) {
                java.awt.geom.Path2D.Double linea = new java.awt.geom.Path2D.Double();
                java.awt.geom.Point2D p0 = proy.apply(camino.get(0)[0], camino.get(0)[1]);
                double x0 = pixelMundo(camino.get(0)[0], camino.get(0)[1], zoom)[0];
                for (int i = 0; i < camino.size(); i++) {
                    double[] q = pixelMundo(camino.get(i)[0], camino.get(i)[1], zoom);   // la ruta sigue sin saltos
                    double x = p0.getX() + (q[0] - x0), y = oy + q[1];
                    if (i == 0) linea.moveTo(x, y); else linea.lineTo(x, y);
                }
                g.setStroke(new BasicStroke(4.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(20, 30, 60, 170));
                g.draw(linea);
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(Color.WHITE);
                g.draw(linea);
                dibujarPunto(g, proy.apply(casa[0], casa[1]), new Color(60, 150, 255));
                java.awt.geom.Point2D pr = proy.apply(remoto[0], remoto[1]);
                dibujarPunto(g, pr, new Color(230, 40, 40));

                double dist = distanciaKm(casa, remoto);
                double sp = rumboGrados(casa, remoto);
                double lp = (sp + 180) % 360;
                java.text.DecimalFormat df = new java.text.DecimalFormat("#,##0",
                        java.text.DecimalFormatSymbols.getInstance(new java.util.Locale("es", "ES")));
                String l1 = String.format("SP %.0f°   LP %.0f°", sp, lp);
                String l2 = "Dist " + df.format(Math.round(dist)) + " km";
                dibujarCaja(g, w, h, (int) pr.getX(), (int) pr.getY(),
                        nombreRemoto.isEmpty() ? new String[]{l1, l2} : new String[]{nombreRemoto, l1, l2});
            } else if (casa != null) {
                dibujarPunto(g, proy.apply(casa[0], casa[1]), new Color(60, 150, 255));
            }

            // Atribución obligatoria de OpenStreetMap (encima de la barra inferior)
            g.setFont(new Font("Arial", Font.PLAIN, 10));
            String atr = "© colaboradores de OpenStreetMap";
            int aw = g.getFontMetrics().stringWidth(atr);
            g.setColor(new Color(255, 255, 255, 200));
            g.fillRect(w - aw - 8, h - 31, aw + 8, 14);
            g.setColor(Color.DARK_GRAY);
            g.drawString(atr, w - aw - 4, h - 20);

            // Barra inferior: mi posición y avisos
            String abajo = casa == null
                    ? "Pon tu Grid o tu QTH en Mi Estación → Operador"
                    : "Mi QTH: " + nombreCasa + (aviso.isEmpty() ? "" : "   |   " + aviso);
            g.setFont(new Font("Arial", Font.PLAIN, 11));
            g.setColor(new Color(0, 0, 0, 150));
            g.fillRect(0, h - 17, w, 17);
            g.setColor(Color.WHITE);
            g.drawString(abajo, 6, h - 5);
            g.dispose();
        }

        private void dibujarPunto(Graphics2D g, java.awt.geom.Point2D p, Color c) {
            int x = (int) Math.round(p.getX()), y = (int) Math.round(p.getY());
            g.setColor(Color.WHITE);
            g.fillOval(x - 6, y - 6, 12, 12);
            g.setColor(c);
            g.fillOval(x - 4, y - 4, 8, 8);
        }

        private void dibujarCaja(Graphics2D g, int w, int h, int x, int y, String[] lineas) {
            g.setFont(new Font("Arial", Font.BOLD, 13));
            FontMetrics fm = g.getFontMetrics();
            int ancho = 0;
            for (String s : lineas) ancho = Math.max(ancho, fm.stringWidth(s));
            int alto = lineas.length * (fm.getHeight()) + 8;
            ancho += 16;
            int bx = x + 12, by = y - alto / 2;
            if (bx + ancho > w - 4) bx = x - 12 - ancho;
            bx = Math.max(4, bx);
            by = Math.max(4, Math.min(by, h - 20 - alto));
            g.setColor(new Color(20, 30, 50, 215));
            g.fillRoundRect(bx, by, ancho, alto, 8, 8);
            g.setColor(new Color(200, 200, 200));
            g.drawRoundRect(bx, by, ancho, alto, 8, 8);
            g.setColor(Color.WHITE);
            int ty = by + 4 + fm.getAscent();
            for (String s : lineas) {
                g.drawString(s, bx + 8, ty);
                ty += fm.getHeight();
            }
        }
    }

    /**
     * Cliente FTP sencillo (solo lo que necesita la sincronización del radioclub): entrar, ir a una carpeta
     * (creándola si no existe), ver los archivos y su tamaño, bajar desde una posición, añadir al final y subir.
     * Con "seguro" usa FTPS explícito (AUTH TLS). El certificado del servidor se guarda la primera vez y
     * después se comprueba que sea el mismo (así vale también un certificado propio del radioclub).
     */
    public static class FtpSimple implements AutoCloseable {
        private final String host;
        private final int puerto;
        private final boolean seguro;
        private java.net.Socket control;
        private java.io.BufferedReader entrada;
        private java.io.Writer salida;
        private javax.net.ssl.SSLSocketFactory fabricaTls;
        private String ultimaRespuesta = "";

        public FtpSimple(String host, int puerto, boolean seguro) {
            this.host = host.trim();
            this.puerto = puerto <= 0 ? 21 : puerto;
            this.seguro = seguro;
        }

        /** Conecta y entra con el usuario. Lanza una excepción con un mensaje sencillo si algo falla. */
        public void entrar(String usuario, String clave) throws Exception {
            java.net.Socket s = new java.net.Socket();
            try {
                s.connect(new java.net.InetSocketAddress(host, puerto), 15000);
            } catch (java.net.UnknownHostException e) {
                throw new Exception("no encuentro el servidor \"" + host + "\"");
            } catch (java.io.IOException e) {
                throw new Exception("no se puede conectar con " + host + ":" + puerto + " (" + e.getMessage() + ")");
            }
            s.setSoTimeout(30000);
            usarControl(s);
            esperar(respuesta(), 220);
            if (seguro) {
                int c = orden("AUTH TLS");
                if (c != 234) throw new Exception("el servidor no admite conexión segura (FTPS): " + ultimaRespuesta);
                javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
                ctx.init(null, new javax.net.ssl.TrustManager[]{new ConfianzaFtp(host)}, null);
                fabricaTls = ctx.getSocketFactory();
                javax.net.ssl.SSLSocket t = (javax.net.ssl.SSLSocket) fabricaTls.createSocket(s, host, puerto, true);
                t.setUseClientMode(true);
                try {
                    t.startHandshake();
                } catch (javax.net.ssl.SSLException e) {
                    String m = e.getMessage() == null ? "" : e.getMessage().replaceFirst("^\\([a-z_]+\\) ", "");
                    throw new Exception("fallo en la conexión segura: " + m);
                }
                usarControl(t);
            }
            int c = orden("USER " + usuario);
            if (c == 331) c = orden("PASS " + clave);
            if (c != 230 && c != 202) {
                String low = ultimaRespuesta.toLowerCase();
                if (!seguro && (low.contains("tls") || low.contains("ssl")))
                    throw new Exception("el servidor exige conexión segura: marca \"Conexión segura (FTPS)\" (" + ultimaRespuesta + ")");
                throw new Exception("el usuario o la contraseña del FTP no son correctos (" + ultimaRespuesta + ")");
            }
            if (seguro) {
                orden("PBSZ 0");
                if (orden("PROT P") != 200) throw new Exception("el servidor no deja cifrar los datos: " + ultimaRespuesta);
            }
            if (orden("TYPE I") != 200) throw new Exception("respuesta inesperada del servidor: " + ultimaRespuesta);
        }

        private void usarControl(java.net.Socket s) throws java.io.IOException {
            control = s;
            entrada = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            salida = new java.io.OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
        }

        /** Va a la carpeta indicada, creando las que falten. */
        public void irACarpeta(String ruta) throws Exception {
            String r = ruta == null ? "" : ruta.trim().replace('\\', '/');
            if (r.startsWith("/")) orden("CWD /");
            for (String parte : r.split("/")) {
                if (parte.isBlank()) continue;
                if (orden("CWD " + parte) == 250) continue;
                orden("MKD " + parte);
                if (orden("CWD " + parte) != 250) throw new Exception("no se puede entrar ni crear la carpeta \"" + parte + "\" en el FTP (" + ultimaRespuesta + ")");
            }
        }

        /** Archivos de la carpeta actual con su tamaño (nombre -> bytes). */
        public Map<String, Long> archivos() throws Exception {
            Map<String, Long> r = new LinkedHashMap<>();
            byte[] lista = null;
            try {
                lista = transferir("MLSD", null, true);
            } catch (Exception ignored) { }
            if (lista != null) {
                for (String l : new String(lista, StandardCharsets.UTF_8).split("\r?\n")) {
                    int sp = l.indexOf(' ');
                    if (sp < 0) continue;
                    String datos = l.substring(0, sp).toLowerCase(), nombre = l.substring(sp + 1);
                    if (!datos.contains("type=file")) continue;
                    long tam = -1;
                    for (String d : datos.split(";")) if (d.startsWith("size=")) try { tam = Long.parseLong(d.substring(5)); } catch (Exception ignored) { }
                    r.put(nombre, tam);
                }
                return r;
            }
            byte[] nombres = transferir("NLST", null, true);
            for (String l : new String(nombres == null ? new byte[0] : nombres, StandardCharsets.UTF_8).split("\r?\n")) {
                String n = l.trim();
                if (n.contains("/")) n = n.substring(n.lastIndexOf('/') + 1);
                if (!n.isEmpty()) r.put(n, tamano(n));
            }
            return r;
        }

        public long tamano(String nombre) throws Exception {
            if (orden("SIZE " + nombre) != 213) return -1;
            try { return Long.parseLong(ultimaRespuesta.substring(4).trim()); } catch (Exception e) { return -1; }
        }

        /** Baja un archivo desde la posición dada. */
        public byte[] bajar(String nombre, long desde) throws Exception {
            if (desde > 0 && orden("REST " + desde) != 350) throw new Exception("el servidor no permite continuar descargas (" + ultimaRespuesta + ")");
            byte[] b = transferir("RETR " + nombre, null, false);
            if (b == null) throw new Exception("no se ha podido bajar " + nombre + " (" + ultimaRespuesta + ")");
            return b;
        }

        /** Añade al final del archivo (lo crea si no existe). */
        public void anadir(String nombre, byte[] datos) throws Exception {
            if (transferir("APPE " + nombre, datos, false) == null) throw new Exception("no se ha podido escribir en el FTP (" + ultimaRespuesta + ")");
        }

        /** Sube el archivo entero (lo sustituye). */
        public void subir(String nombre, byte[] datos) throws Exception {
            if (transferir("STOR " + nombre, datos, false) == null) throw new Exception("no se ha podido escribir en el FTP (" + ultimaRespuesta + ")");
        }

        /** Abre la conexión de datos, envía la orden y lee (o escribe) los datos. Devuelve null si el servidor la rechaza. */
        private byte[] transferir(String ordenFtp, byte[] enviar, boolean silenciosa) throws Exception {
            int puertoDatos;
            if (orden("EPSV") == 229) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(\\|\\|\\|(\\d+)\\|\\)").matcher(ultimaRespuesta);
                if (!m.find()) throw new Exception("respuesta EPSV rara: " + ultimaRespuesta);
                puertoDatos = Integer.parseInt(m.group(1));
            } else {
                if (orden("PASV") != 227) throw new Exception("el servidor no admite el modo pasivo (" + ultimaRespuesta + ")");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").matcher(ultimaRespuesta);
                if (!m.find()) throw new Exception("respuesta PASV rara: " + ultimaRespuesta);
                puertoDatos = Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6));
            }
            java.net.Socket datos = new java.net.Socket();
            datos.connect(new java.net.InetSocketAddress(control.getInetAddress(), puertoDatos), 15000);
            datos.setSoTimeout(30000);
            try {
                int c = orden(ordenFtp);
                if (c != 150 && c != 125) {
                    datos.close();
                    return null;
                }
                java.net.Socket d = datos;
                if (seguro) {
                    // Se usa el puerto de control para que el servidor reconozca la misma sesión TLS (muchos lo exigen)
                    javax.net.ssl.SSLSocket t = (javax.net.ssl.SSLSocket) fabricaTls.createSocket(datos, host, puerto, true);
                    t.setUseClientMode(true);
                    t.startHandshake();
                    d = t;
                }
                byte[] leido;
                if (enviar != null) {
                    java.io.OutputStream os = d.getOutputStream();
                    os.write(enviar);
                    os.flush();
                    leido = new byte[0];
                } else {
                    leido = d.getInputStream().readAllBytes();
                }
                d.close();
                int fin = respuesta();
                if (fin != 226 && fin != 250) return null;
                return leido;
            } finally {
                try { datos.close(); } catch (Exception ignored) { }
            }
        }

        private int orden(String o) throws Exception {
            salida.write(o + "\r\n");
            salida.flush();
            return respuesta();
        }

        private int respuesta() throws Exception {
            String l = entrada.readLine();
            if (l == null) throw new Exception("el servidor FTP ha cortado la conexión");
            StringBuilder sb = new StringBuilder(l);
            if (l.length() >= 4 && l.charAt(3) == '-') {
                String fin = l.substring(0, 3) + " ";
                while ((l = entrada.readLine()) != null) {
                    sb.append('\n').append(l);
                    if (l.startsWith(fin)) break;
                }
                if (l != null) sb = new StringBuilder(l);
            }
            ultimaRespuesta = sb.toString();
            try { return Integer.parseInt(ultimaRespuesta.substring(0, 3)); } catch (Exception e) { return 0; }
        }

        private static void esperar(int c, int bueno) throws Exception {
            if (c != bueno) throw new Exception("el servidor no responde como un FTP (" + c + ")");
        }

        @Override public void close() {
            try { if (salida != null) { salida.write("QUIT\r\n"); salida.flush(); } } catch (Exception ignored) { }
            try { if (control != null) control.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * Comprueba el certificado del FTP seguro: vale si es un certificado reconocido o si es el mismo que la primera vez
     * (la huella se guarda al conectar por primera vez).
     */
    static class ConfianzaFtp implements javax.net.ssl.X509TrustManager {
        private final String host;
        private javax.net.ssl.X509TrustManager normal;

        ConfianzaFtp(String host) {
            this.host = host;
            try {
                javax.net.ssl.TrustManagerFactory f = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                f.init((java.security.KeyStore) null);
                for (javax.net.ssl.TrustManager t : f.getTrustManagers()) if (t instanceof javax.net.ssl.X509TrustManager x) normal = x;
            } catch (Exception ignored) { }
        }

        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }

        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] cadena, String tipo) throws java.security.cert.CertificateException {
            if (cadena == null || cadena.length == 0) throw new java.security.cert.CertificateException("el servidor no presenta certificado");
            String huella;
            try {
                huella = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(cadena[0].getEncoded()));
            } catch (Exception e) {
                throw new java.security.cert.CertificateException(e);
            }
            String clave = "RC_FTP_HUELLA";
            String guardada = ConfigApp.get(clave, "");
            String guardadaHost = ConfigApp.get("RC_FTP_HUELLA_SERVIDOR", "");
            if (!guardada.isEmpty() && guardadaHost.equalsIgnoreCase(host)) {
                if (guardada.equals(huella)) return;
                throw new java.security.cert.CertificateException("el certificado del servidor ha cambiado: puede que no sea el servidor del radioclub");
            }
            try {
                if (normal != null) normal.checkServerTrusted(cadena, tipo);
            } catch (Exception ignored) { }
            // Primera vez con este servidor: se recuerda su certificado
            ConfigApp.put(clave, huella);
            ConfigApp.put("RC_FTP_HUELLA_SERVIDOR", host);
        }

        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
    }

    public static class ConfigApp {
        private static final String FICHERO = "config.properties";

        private static File obtenerFichero() {
            File docsDir = new File(System.getProperty("user.home"),
                    "Documents" + File.separator + NOMBRE_APP);
            if (!docsDir.exists()) docsDir.mkdirs();
            return new File(docsDir, FICHERO);
        }

        private static java.util.Properties cargar() {
            java.util.Properties props = new java.util.Properties();
            File f = obtenerFichero();
            if (!f.exists()) return props;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                props.load(fis);
            } catch (Exception e) {
                System.err.println("No se pudo leer " + FICHERO + ": " + e.getMessage());
            }
            return props;
        }

        private static void guardar(java.util.Properties props) {
            File f = obtenerFichero();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                props.store(fos, "Configuracion de " + NOMBRE_APP);
            } catch (Exception e) {
                System.err.println("No se pudo escribir " + FICHERO + ": " + e.getMessage());
            }
        }

        public static String get(String clave, String defecto) {
            String v = cargar().getProperty(clave);
            return (v == null) ? defecto : v;
        }

        public static void put(String clave, String valor) {
            java.util.Properties props = cargar();
            props.setProperty(clave, valor == null ? "" : valor);
            guardar(props);
        }

        /** Quita una línea de la configuración (para no dejar datos que ya no se usan). */
        public static void quitar(String clave) {
            java.util.Properties props = cargar();
            if (props.remove(clave) != null) guardar(props);
        }

        public static int getInt(String clave, int defecto) {
            String v = cargar().getProperty(clave);
            if (v == null) return defecto;
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return defecto;
            }
        }

        public static void putInt(String clave, int valor) {
            java.util.Properties props = cargar();
            props.setProperty(clave, String.valueOf(valor));
            guardar(props);
        }
    }

    public static class DatabaseManager {
        public static String obtenerRutaActiva() {
            File docsDir = new File(System.getProperty("user.home"),
                    "Documents" + File.separator + NOMBRE_APP);
            if (!docsDir.exists()) {
                docsDir.mkdirs();
            }
            return new File(docsDir, NOMBRE_FICHERO_BD).getAbsolutePath();
        }

        public static Connection conectar() throws Exception {
            Class.forName("org.sqlite.JDBC");
            String url = "jdbc:sqlite:" + obtenerRutaActiva();
            Connection c = java.sql.DriverManager.getConnection(url);
            // Si otra parte del programa (p. ej. la sincronización) está escribiendo, se espera un poco en vez de fallar
            try (Statement st = c.createStatement()) { st.execute("PRAGMA busy_timeout = 30000"); } catch (Exception ignored) { }
            return c;
        }

        /**
         * Copia de seguridad automática de la base de datos: una al día, al abrir el programa, en la carpeta
         * "Copias de seguridad" (junto a la base de datos). Se guardan las 15 últimas. Si algo falla, no pasa nada.
         */
        static void copiaDeSeguridadDiaria() {
            try {
                File bd = new File(obtenerRutaActiva());
                if (!bd.isFile() || bd.length() == 0) return;
                File dir = new File(bd.getParentFile(), "Copias de seguridad");
                if (!dir.isDirectory() && !dir.mkdirs()) return;
                String hoy = java.time.LocalDate.now().toString();
                File copia = new File(dir, "libro_guardia_" + hoy + ".db");
                if (copia.exists()) return;
                java.nio.file.Files.copy(bd.toPath(), copia.toPath());
                File[] todas = dir.listFiles((x, n) -> n.startsWith("libro_guardia_") && n.endsWith(".db"));
                if (todas != null && todas.length > 15) {
                    java.util.Arrays.sort(todas);
                    for (int i = 0; i < todas.length - 15; i++) todas[i].delete();
                }
            } catch (Exception ignored) { }
        }

        public static void inicializarBaseDatos() {
            copiaDeSeguridadDiaria();
            try (Connection conn = conectar();
                 Statement stmt = conn.createStatement()) {

                stmt.execute("CREATE TABLE IF NOT EXISTS actividades (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "nombre TEXT UNIQUE NOT NULL, " +
                        "tipo TEXT DEFAULT 'SIMPLE', " +
                        "operador_creador TEXT DEFAULT '', " +
                        "umbral_bronce INTEGER DEFAULT 0, " +
                        "umbral_plata INTEGER DEFAULT 0, " +
                        "umbral_oro INTEGER DEFAULT 0, " +
                        "cat6_nombre TEXT DEFAULT '', " +
                        "cat6_umbral INTEGER DEFAULT 0, " +
                        "numeral INTEGER DEFAULT 0, " +
                        "fecha_creacion TEXT DEFAULT '', " +
                        "banda_simple TEXT DEFAULT '', " +
                        "modo_simple TEXT DEFAULT '')");
                stmt.execute("CREATE TABLE IF NOT EXISTS qsos (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "uuid TEXT, " +
                        "operador TEXT DEFAULT '', " +
                        "fecha TEXT, hora TEXT, indicativo TEXT, banda TEXT, modo TEXT, " +
                        "freq_tx TEXT, freq_rx TEXT, rst_sent TEXT, rst_rcvd TEXT, " +
                        "nombre TEXT, qth TEXT, grid TEXT, actividad TEXT, " +
                        "subido INTEGER DEFAULT 0, " +
                        "actividad_numeral INTEGER DEFAULT 0, " +
                        "puntos INTEGER DEFAULT 0, " +
                        "num_contactos INTEGER DEFAULT 1)");

                try { stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_qsos_uuid ON qsos(uuid)"); } catch (Exception ignored) {}
                try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_qsos_actividad ON qsos(actividad)"); } catch (Exception ignored) {}
                stmt.execute("CREATE TABLE IF NOT EXISTS cb_directorio (" +
                        "indicativo TEXT PRIMARY KEY, " +
                        "nombre TEXT DEFAULT '', " +
                        "qth TEXT DEFAULT '', " +
                        "eliminado INTEGER DEFAULT 0, " +
                        "corte INTEGER DEFAULT 0)");

                try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_qsos_operador_act ON qsos(operador, actividad)"); } catch (Exception ignored) {}

                stmt.execute("CREATE TABLE IF NOT EXISTS actividad_puntos (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "actividad TEXT NOT NULL, " +
                        "tipo TEXT NOT NULL, " +
                        "valor TEXT NOT NULL, " +
                        "puntos INTEGER NOT NULL DEFAULT 1, " +
                        "UNIQUE(actividad, tipo, valor))");

                try { stmt.execute("ALTER TABLE qsos ADD COLUMN uuid TEXT"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE qsos ADD COLUMN operador TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE qsos ADD COLUMN subido INTEGER DEFAULT 0"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE actividades ADD COLUMN operador_creador TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE actividades ADD COLUMN umbral_bronce INTEGER DEFAULT 0"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE actividades ADD COLUMN umbral_plata INTEGER DEFAULT 0"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE actividades ADD COLUMN umbral_oro INTEGER DEFAULT 0"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE actividades ADD COLUMN cat6_nombre TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE actividades ADD COLUMN cat6_umbral INTEGER DEFAULT 0"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE actividades ADD COLUMN numeral INTEGER DEFAULT 0"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE actividades ADD COLUMN fecha_creacion TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE qsos ADD COLUMN actividad_numeral INTEGER DEFAULT 0"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE actividades ADD COLUMN banda_simple TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE actividades ADD COLUMN modo_simple TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE qsos ADD COLUMN puntos INTEGER DEFAULT 0"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE qsos ADD COLUMN num_contactos INTEGER DEFAULT 1"); } catch (Exception ignored) {}

                try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_qsos_actividad_numeral ON qsos(actividad_numeral)"); } catch (Exception ignored) {}

                // RADIOCLUB: marca de contacto/actividad del radioclub e indicativo de estación usado
                try { stmt.execute("ALTER TABLE qsos ADD COLUMN radioclub INTEGER DEFAULT 0"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE qsos ADD COLUMN estacion TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE actividades ADD COLUMN radioclub INTEGER DEFAULT 0"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE actividades ADD COLUMN indicativo_rc TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_qsos_radioclub ON qsos(radioclub)"); } catch (Exception ignored) {}

                // MAPA: posiciones de QTH ya consultadas (para no preguntar dos veces a internet)
                try { stmt.execute("CREATE TABLE IF NOT EXISTS geo_cache (clave TEXT PRIMARY KEY, lat REAL, lon REAL, encontrado INTEGER DEFAULT 1)"); } catch (Exception ignored) {}

                // SOCIOS DEL RADIOCLUB y el pago de la cuota de cada año
                try { stmt.execute("CREATE TABLE IF NOT EXISTS socios (id INTEGER PRIMARY KEY AUTOINCREMENT, num INTEGER, "
                        + "ind_hf TEXT DEFAULT '', ind_cb TEXT DEFAULT '', nombre TEXT DEFAULT '', qth TEXT DEFAULT '')"); } catch (Exception ignored) {}
                try { stmt.execute("CREATE TABLE IF NOT EXISTS socios_cuotas (socio_id INTEGER, anio INTEGER, fecha_pago TEXT DEFAULT '', "
                        + "PRIMARY KEY (socio_id, anio))"); } catch (Exception ignored) {}
                // CARGOS del radioclub (1 = Superadministrador, 2 = Administrador) con su contraseña (cifrada)
                try { stmt.execute("CREATE TABLE IF NOT EXISTS rc_cargos (id INTEGER PRIMARY KEY AUTOINCREMENT, indicativo TEXT, "
                        + "nombre TEXT DEFAULT '', grado INTEGER, sal TEXT, clave TEXT)"); } catch (Exception ignored) {}
                // COMPARTIR CON EL RADIOCLUB: identificadores únicos y estado de la sincronización
                try { stmt.execute("ALTER TABLE rc_cargos ADD COLUMN uuid TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE rc_cargos ADD COLUMN pub TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE rc_cargos ADD COLUMN priv TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE rc_cargos ADD COLUMN sal2 TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE socios ADD COLUMN uuid TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE actividades ADD COLUMN uuid TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE qsos ADD COLUMN act_uuid TEXT DEFAULT ''"); } catch (Exception ignored) {}
                // Quién cambió el contacto por última vez (columna "Modificado")
                try { stmt.execute("ALTER TABLE qsos ADD COLUMN modificado_por TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE qsos ADD COLUMN comentario TEXT DEFAULT ''"); } catch (Exception ignored) {}
                // Cuenta Bitácora 26: 0 = falta subirlo, 1 = subido, 2 = no es de ninguna cuenta de este ordenador
                try { stmt.execute("ALTER TABLE qsos ADD COLUMN b26_sub INTEGER DEFAULT 0"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE qsos ADD COLUMN b26_cuenta TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE qsos ADD COLUMN b26_conf TEXT DEFAULT ''"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE qsos ADD COLUMN b26_qsl INTEGER DEFAULT 0"); } catch (Exception ignored) {}
                try { stmt.execute("CREATE TABLE IF NOT EXISTS b26_borrados (uuid TEXT PRIMARY KEY, cuenta TEXT DEFAULT '')"); } catch (Exception ignored) {}
                // QSL que han llegado (el fichero está en Documentos\Libro de Guardia\QSL)
                try { stmt.execute("CREATE TABLE IF NOT EXISTS qsl_recibidas (id INTEGER PRIMARY KEY AUTOINCREMENT, fichero TEXT, de TEXT, cuenta TEXT, "
                        + "minuto INTEGER, banda TEXT, modo TEXT, uuid_contacto TEXT DEFAULT '', llegada INTEGER, vista INTEGER DEFAULT 0)"); } catch (Exception ignored) {}
                // De dónde vino la QSL: '' = Bitácora 26, 'eQSL' = bajada de eQSL.cc
                try { stmt.execute("ALTER TABLE qsl_recibidas ADD COLUMN origen TEXT DEFAULT ''"); } catch (Exception ignored) {}
                // Al borrar un contacto ya subido, se apunta para quitarlo también del servidor
                try { stmt.execute("CREATE TRIGGER IF NOT EXISTS b26_al_borrar AFTER DELETE ON qsos WHEN COALESCE(OLD.b26_sub,0) = 1 AND COALESCE(OLD.uuid,'') <> '' "
                        + "BEGIN INSERT OR REPLACE INTO b26_borrados (uuid, cuenta) VALUES (OLD.uuid, COALESCE(OLD.b26_cuenta,'')); END"); } catch (Exception ignored) {}
                // Si cambia algo de lo que se cruza, se vuelve a subir
                try { stmt.execute("CREATE TRIGGER IF NOT EXISTS b26_al_cambiar AFTER UPDATE OF indicativo, fecha, hora, banda, modo, operador, estacion ON qsos "
                        + "WHEN COALESCE(OLD.b26_sub,0) <> 0 BEGIN UPDATE qsos SET b26_sub = 0 WHERE id = NEW.id; END"); } catch (Exception ignored) {}
                // Ordenadores expulsados del radioclub (sus cambios ya no se aceptan)
                try { stmt.execute("CREATE TABLE IF NOT EXISTS rc_expulsados (dev TEXT PRIMARY KEY, nota TEXT DEFAULT '')"); } catch (Exception ignored) {}
                // Administradores del radioclub (los nombra el ordenador del radioclub): desde / hasta cuándo lo son
                try { stmt.execute("CREATE TABLE IF NOT EXISTS rc_admins (dev TEXT PRIMARY KEY, nota TEXT DEFAULT '', desde INTEGER DEFAULT 0, hasta INTEGER DEFAULT 0)"); } catch (Exception ignored) {}
                // Cargo: ADMIN (administrador) o SUPER (superadministrador: como el ordenador del radioclub)
                try { stmt.execute("ALTER TABLE rc_admins ADD COLUMN rol TEXT DEFAULT 'ADMIN'"); } catch (Exception ignored) {}
                // Contraseñas temporales de operadores (las pone el radioclub o un superadministrador)
                try { stmt.execute("CREATE TABLE IF NOT EXISTS rc_claves_temp (op TEXT PRIMARY KEY, sal TEXT, hash TEXT, f INTEGER DEFAULT 0)"); } catch (Exception ignored) {}
                try { stmt.execute("CREATE TABLE IF NOT EXISTS sync_pub (tipo TEXT, clave TEXT, datos TEXT, PRIMARY KEY (tipo, clave))"); } catch (Exception ignored) {}
                try { stmt.execute("CREATE TABLE IF NOT EXISTS sync_ver (tipo TEXT, clave TEXT, f INTEGER, dev TEXT, PRIMARY KEY (tipo, clave))"); } catch (Exception ignored) {}
                try { stmt.execute("CREATE TABLE IF NOT EXISTS sync_leido (fichero TEXT PRIMARY KEY, pos INTEGER)"); } catch (Exception ignored) {}
                try { stmt.execute("CREATE TABLE IF NOT EXISTS sync_pend (id INTEGER PRIMARY KEY AUTOINCREMENT, linea TEXT)"); } catch (Exception ignored) {}
                // SINCRONIZAR MIS ORDENADORES (el log de un operador en varios ordenadores)
                try { stmt.execute("CREATE TABLE IF NOT EXISTS sync_op_pub (op TEXT, tipo TEXT, clave TEXT, datos TEXT, PRIMARY KEY (op, tipo, clave))"); } catch (Exception ignored) {}
                // Buzón con el móvil: cambios de aquí que aún no se han podido mandar
                try { stmt.execute("CREATE TABLE IF NOT EXISTS sync_buzon_pend (id INTEGER PRIMARY KEY AUTOINCREMENT, op TEXT, texto TEXT)"); } catch (Exception ignored) {}
                try { stmt.execute("CREATE TABLE IF NOT EXISTS sync_op_ver (op TEXT, tipo TEXT, clave TEXT, f INTEGER, PRIMARY KEY (op, tipo, clave))"); } catch (Exception ignored) {}
                try { stmt.execute("CREATE TABLE IF NOT EXISTS sync_op_leido (op TEXT, fichero TEXT, pos INTEGER, PRIMARY KEY (op, fichero))"); } catch (Exception ignored) {}
                try { stmt.execute("CREATE TABLE IF NOT EXISTS sync_op_alias (op TEXT, tipo TEXT, remoto TEXT, local TEXT, PRIMARY KEY (op, tipo, remoto))"); } catch (Exception ignored) {}
                // REGISTRO de cambios del radioclub (quién, qué, cuándo) y lo necesario para deshacerlo
                try { stmt.execute("CREATE TABLE IF NOT EXISTS rc_registro (id INTEGER PRIMARY KEY AUTOINCREMENT, fecha TEXT, quien TEXT, "
                        + "accion TEXT, tipo TEXT DEFAULT '', datos TEXT DEFAULT '', deshecho INTEGER DEFAULT 0)"); } catch (Exception ignored) {}
                try { stmt.execute("ALTER TABLE geo_cache ADD COLUMN pais TEXT DEFAULT ''"); } catch (Exception ignored) {}

            } catch (Exception e) {
                System.err.println("Error al inicializar la base de datos: " + e.getMessage());
            }
        }
    }

    /**
     * Fuentes instaladas en el ordenador (Windows\\Fonts y las del usuario). Se buscan una sola vez, en segundo plano,
     * la primera vez que se abre el generador de diplomas. Para el PDF hace falta el archivo de la fuente (.ttf / .otf).
     */
    static class FuentesSistema {
        private static volatile Map<String, Map<String, File>> catalogo;
        private static final Map<String, Font> cacheAwt = new java.util.concurrent.ConcurrentHashMap<>();

        static synchronized Map<String, Map<String, File>> catalogo() {
            if (catalogo != null) return catalogo;
            Map<String, Map<String, File>> m = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            java.util.List<File> carpetas = new ArrayList<>();
            String win = System.getenv("WINDIR");
            if (win != null) carpetas.add(new File(win, "Fonts"));
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) carpetas.add(new File(local, "Microsoft" + File.separator + "Windows" + File.separator + "Fonts"));
            carpetas.add(new File("/usr/share/fonts"));
            carpetas.add(new File(System.getProperty("user.home"), ".fonts"));
            carpetas.add(new File(System.getProperty("user.home"), ".local/share/fonts"));
            java.util.ArrayDeque<File> pendientes = new java.util.ArrayDeque<>(carpetas);
            while (!pendientes.isEmpty()) {
                File d = pendientes.poll();
                File[] hijos = d.listFiles();
                if (hijos == null) continue;
                for (File f : hijos) {
                    if (f.isDirectory()) { pendientes.add(f); continue; }
                    String n = f.getName().toLowerCase();
                    if (!n.endsWith(".ttf") && !n.endsWith(".otf")) continue;
                    try {
                        Font fu = Font.createFont(Font.TRUETYPE_FONT, f);
                        String familia = fu.getFamily(java.util.Locale.ROOT).trim();
                        if (familia.isEmpty() || familia.startsWith(".")) continue;
                        String nombre = (fu.getFontName(java.util.Locale.ROOT) + " " + f.getName()).toLowerCase();
                        boolean negrita = nombre.contains("bold") || nombre.contains("black") || nombre.contains("heavy");
                        boolean cursiva = nombre.contains("italic") || nombre.contains("oblique");
                        String estilo = negrita && cursiva ? "Negrita-Cursiva" : negrita ? "Negrita" : cursiva ? "Cursiva" : "Normal";
                        Map<String, File> estilos = m.computeIfAbsent(familia, k -> new HashMap<>());
                        // De cada estilo se queda el primero; para "Normal" se prefiere el archivo "Regular"
                        if (!estilos.containsKey(estilo) || (estilo.equals("Normal") && nombre.contains("regular"))) estilos.put(estilo, f);
                    } catch (Exception | Error ignored) {
                        // archivo de fuente que Java no puede leer: se salta
                    }
                }
            }
            // Las tres de siempre no se repiten
            m.remove("Helvetica"); m.remove("Times-Roman"); m.remove("Courier");
            catalogo = m;
            return m;
        }

        static java.util.List<String> familias() {
            return new ArrayList<>(catalogo().keySet());
        }

        /** Archivo de la fuente para ese estilo; con "conRespaldo", si no tiene ese estilo devuelve el normal (o el que haya). */
        static File archivo(String familia, String estilo, boolean conRespaldo) {
            Map<String, Map<String, File>> c = catalogo;
            if (c == null || familia == null) return null;
            Map<String, File> e = c.get(familia);
            if (e == null) return null;
            File f = e.get(estilo);
            if (f == null && conRespaldo) f = e.getOrDefault("Normal", e.values().stream().findFirst().orElse(null));
            return f;
        }

        /** La fuente para verla en pantalla (tamaño 1: hay que usar deriveFont). null si no es del ordenador. */
        static Font fuenteAwt(String familia, String estilo) {
            File f = archivo(familia, estilo, true);
            if (f == null) return null;
            return cacheAwt.computeIfAbsent(f.getAbsolutePath(), k -> {
                try { return Font.createFont(Font.TRUETYPE_FONT, f); } catch (Exception ex) { return new Font("SansSerif", Font.PLAIN, 12); }
            });
        }
    }

}