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

public class GeneradorDiplomaUI extends JFrame {

    private final JTextField txtActivacion;
    private final JComboBox<String> cbHoraDiploma = new JComboBox<>(new String[]{"UTC", "LOCAL"});
    private final JTextField txtAdi;
    private final JTextField txtImagen;
    private final JTextField txtDestino;
    private final JTextArea txtLog;
    private final PanelDiseno panelDiseno;
    private final JLabel lblCampoSeleccionado;
    private final JComboBox<String> cbFuente;
    private final JComboBox<String> cbEstilo;
    private final JSpinner spinTamano;
    private final JButton btnColorTexto;
    private final JButton btnColorFondo;
    private final JSpinner spinTransparencia;
    private final JSpinner spinRadio;
    private final JSpinner spinPosX;
    private final JSpinner spinPosY;
    private final JSpinner spinAnchoCaja;
    private final JSpinner spinAltoCaja;
    private final JCheckBox chkColoresAutomaticos;
    private final JButton btnConfigurarColores;
    private final Map<String, Color> coloresBanda = new LinkedHashMap<>();
    private final Map<String, Color> coloresModo = new LinkedHashMap<>();

    public void usarHoraLocalPorDefecto() {
        cbHoraDiploma.setSelectedItem("LOCAL");
    }

    private static class PropiedadesCampo {
        JLabel label;
        JCheckBox chkVisible;
        String familiaFuente = "Helvetica";
        String estiloFuente = "Negrita";
        int tamanoFuente = 16;
        Color colorTexto = Color.RED;
        int posXOriginal = 50;
        int posYOriginal = 50;
        int anchoCaja = 200;
        int altoCaja = 40;
        boolean visible = true;
        // Fondo del campo: color, transparencia (100 = sin fondo) y radio de las esquinas (en píxeles de la plantilla)
        Color colorFondo = Color.WHITE;
        int transparenciaFondo = 100;
        int radioEsquinas = 0;
    }

    /** Etiqueta de un campo en la vista previa: pinta el fondo (color, transparencia y esquinas redondeadas) como saldrá en el PDF. */
    private class EtiquetaCampo extends JLabel {
        PropiedadesCampo prop;
        EtiquetaCampo(String texto) { super(texto, SwingConstants.CENTER); setOpaque(false); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int b = BordeConNodos.NODO / 2;
            if (prop != null && prop.transparenciaFondo < 100) {
                int alfa = (int) Math.round(255 * (100 - prop.transparenciaFondo) / 100.0);
                Color c = prop.colorFondo;
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alfa));
                float rad = (float) (prop.radioEsquinas * panelDiseno.getEscala()) * 2f;
                g.fill(new java.awt.geom.RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), rad, rad));
            } else if (prop != null && !prop.equals(camposMap.get(idCampoSeleccionado))) {
                // Sin fondo: un contorno muy suave para saber dónde está la caja (no sale en el PDF)
                g.setColor(new Color(120, 120, 120, 90));
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{3f, 3f}, 0f));
                g.drawRect(b, b, getWidth() - 2 * b - 1, getHeight() - 2 * b - 1);
            }
            g.dispose();
            super.paintComponent(g0);
        }
    }

    private final Map<String, PropiedadesCampo> camposMap = new HashMap<>();
    private final Map<String, JLabel> nombresCampo = new HashMap<>();
    private String idCampoSeleccionado = null;
    private boolean actualizandoUI = false;

    public GeneradorDiplomaUI(String activacionPredefinida, File archivoAdiPredefinido) {
        coloresBanda.put("160M", new Color(139, 0, 0));
        coloresBanda.put("80M", new Color(255, 140, 0));
        coloresBanda.put("40M", new Color(255, 200, 0));
        coloresBanda.put("20M", new Color(144, 238, 144));
        coloresBanda.put("15M", new Color(50, 205, 50));
        coloresBanda.put("10M", new Color(0, 206, 209));
        coloresBanda.put("6M", new Color(30, 144, 255));
        coloresBanda.put("2M", new Color(138, 43, 226));
        coloresBanda.put("70CM", new Color(255, 0, 255));

        coloresModo.put("SSB", new Color(220, 20, 60));
        coloresModo.put("LSB", new Color(50, 200, 187));
        coloresModo.put("USB", new Color(180, 0, 100));
        coloresModo.put("FM", new Color(255, 140, 0));
        coloresModo.put("AM", new Color(211, 181, 0));
        coloresModo.put("CW", new Color(34, 139, 34));

        setTitle("Generador de Diplomas QSO - Editor Visual Interactivo [created by EA4IXR Apósitos]");
        setSize(1350, 900);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        JPanel panelForm = new JPanel(new GridLayout(5, 3, 5, 5));
        panelForm.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        txtActivacion = new JTextField(activacionPredefinida != null ? activacionPredefinida : "");
        ((AbstractDocument) txtActivacion.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
                if (string != null) super.insertString(fb, offset, string.toUpperCase(), attr);
            }
            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                if (text != null) super.replace(fb, offset, length, text.toUpperCase(), attrs);
            }
        });

        txtAdi = new JTextField(archivoAdiPredefinido != null ? archivoAdiPredefinido.getAbsolutePath() : "");
        txtImagen = new JTextField();
        txtDestino = new JTextField(archivoAdiPredefinido != null && archivoAdiPredefinido.getParent() != null ? archivoAdiPredefinido.getParent() : "");

        JButton btnBuscarAdi = new JButton("Examinar...");
        JButton btnBuscarImagen = new JButton("Examinar...");
        JButton btnBuscarDestino = new JButton("Examinar...");

        btnBuscarAdi.addActionListener(ignored -> seleccionarArchivoLog(txtAdi));
        btnBuscarImagen.addActionListener(ignored -> cargarImagenSeleccionada());
        btnBuscarDestino.addActionListener(ignored -> seleccionarCarpeta(txtDestino));

        panelForm.add(new JLabel("Activación:"));
        panelForm.add(txtActivacion);
        // El selector de hora (UTC/LOCAL) está en el recuadro "Visibilidad de Campos", junto a Banda
        panelForm.add(new JLabel(""));
        panelForm.add(new JLabel("Archivo LOG (.adi):"));
        panelForm.add(txtAdi);
        panelForm.add(btnBuscarAdi);
        panelForm.add(new JLabel("Plantilla Imagen:"));
        panelForm.add(txtImagen);
        panelForm.add(btnBuscarImagen);
        panelForm.add(new JLabel("Carpeta Guardado:"));
        panelForm.add(txtDestino);
        panelForm.add(btnBuscarDestino);

        JButton btnGenerar = new JButton("Generar Diplomas PDF");
        btnGenerar.setFont(new Font("Arial", Font.BOLD, 14));
        btnGenerar.setBackground(new Color(34, 139, 34));
        btnGenerar.setForeground(Color.WHITE);
        btnGenerar.addActionListener(ignored -> procesarDiplomas());

        JLabel lblFirma = new JLabel("created by EA4IXR Apósitos", SwingConstants.RIGHT);
        lblFirma.setFont(new Font("Arial", Font.ITALIC, 11));
        lblFirma.setForeground(new Color(100, 100, 100));
        JPanel celdaAyuda = new JPanel(new BorderLayout(8, 0));
        celdaAyuda.add(new JLabel("Pincha un campo de la imagen para editarlo ->"), BorderLayout.CENTER);
        panelForm.add(celdaAyuda);

        JPanel panelAccionDerecha = new JPanel(new BorderLayout(10, 0));
        // Aceptar: deja el diploma configurado y pregunta si se crean solos al añadir cada contacto o todos a la vez
        JButton btnAceptarDip = new JButton("Aceptar");
        btnAceptarDip.setFont(new Font("Arial", Font.BOLD, 14));
        btnAceptarDip.setToolTipText("Guarda la configuración del diploma de esta actividad");
        btnAceptarDip.addActionListener(ignored -> aceptarConfiguracion());
        JPanel botonesDip = new JPanel(new GridLayout(1, 2, 8, 0));
        botonesDip.add(btnAceptarDip);
        botonesDip.add(btnGenerar);
        panelAccionDerecha.add(botonesDip, BorderLayout.CENTER);
        // La firma va a la derecha de la fila de ayuda, para que quepan bien los dos botones
        celdaAyuda.add(lblFirma, BorderLayout.EAST);

        panelForm.add(panelAccionDerecha);
        add(panelForm, BorderLayout.NORTH);

        panelDiseno = new PanelDiseno();
        panelDiseno.setLayout(null);
        panelDiseno.setBorder(BorderFactory.createEtchedBorder());
        add(panelDiseno, BorderLayout.CENTER);

        // Panel de la derecha: ancho fijo y, si la ventana es baja, se desplaza hacia abajo (no se esconde nada)
        JPanel panelDerecho = new JPanel() {
            @Override public Dimension getPreferredSize() {
                return new Dimension(310, super.getPreferredSize().height);
            }
        };
        panelDerecho.setLayout(new BoxLayout(panelDerecho, BoxLayout.Y_AXIS));

        JPanel panelVisibilidad = new JPanel(new GridBagLayout());
        panelVisibilidad.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Visibilidad de Campos", TitledBorder.LEFT, TitledBorder.TOP, new Font("Arial", Font.BOLD, 12)));
        panelVisibilidad.setMaximumSize(new Dimension(310, 236));

        crearCampoConVisibilidad(panelVisibilidad, "CALL", "Indicativo", "EA1DEMO", 50, 50, "Negrita", 28, new Color(0, 0, 255), 0, 0);
        crearCampoConVisibilidad(panelVisibilidad, "QRA", "QRA (Nombre)", "JUAN EJEMPLO", 350, 50, "Negrita", 16, new Color(0, 0, 255), 0, 1);
        crearCampoConVisibilidad(panelVisibilidad, "DATE", "Fecha", "10/01/1985", 50, 100, "Negrita", 16, new Color(0, 0, 255), 0, 2);
        crearCampoConVisibilidad(panelVisibilidad, "TIME", "Hora", "14:02 UTC", 200, 100, "Negrita", 16, new Color(0, 0, 255), 0, 3);
        crearCampoConVisibilidad(panelVisibilidad, "BAND", "Banda", "40M", 350, 100, "Negrita", 16, new Color(0, 0, 255), 0, 4);
        crearCampoConVisibilidad(panelVisibilidad, "QTH", "QTH", "QTH", 500, 140, "Negrita", 16, new Color(0, 0, 255), 1, 0);
        crearCampoConVisibilidad(panelVisibilidad, "MODE", "Modo", "SSB", 50, 140, "Negrita", 16, new Color(0, 0, 255), 1, 1);
        crearCampoConVisibilidad(panelVisibilidad, "RST_SENT", "RST Send", "59", 200, 140, "Negrita", 16, new Color(0, 0, 255), 1, 2);
        crearCampoConVisibilidad(panelVisibilidad, "RST_RCVD", "RST TX", "59", 350, 140, "Negrita", 16, new Color(0, 0, 255), 1, 3);
        crearCampoConVisibilidad(panelVisibilidad, "FREQ", "Frecuencia", "7.150 MHz", 500, 100, "Negrita", 16, new Color(0, 0, 255), 1, 4);
        {   // La frecuencia es nueva: sale oculta, para no descolocar los diplomas que ya estaban configurados
            PropiedadesCampo pf = camposMap.get("FREQ");
            pf.visible = false;
            pf.chkVisible.setSelected(false);
            pf.label.setVisible(false);
        }
        // Si no hay plantilla elegida, se pone la de Bitácora 26 con los campos ya colocados (así es más intuitivo)
        SwingUtilities.invokeLater(this::cargarPlantillaPorDefecto);

        // Selector de hora del diploma (UTC/LOCAL), en su propia fila debajo de las casillas
        JPanel panelHoraDiploma = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panelHoraDiploma.setOpaque(false);
        JLabel lblHoraDiploma = new JLabel("Hora:");
        lblHoraDiploma.setFont(new Font("Arial", Font.BOLD, 12));
        lblHoraDiploma.setForeground(UIManager.getColor("CheckBox.foreground"));
        cbHoraDiploma.setFont(new Font("Arial", Font.BOLD, 12));
        cbHoraDiploma.setToolTipText("Hora que se imprime en el diploma: UTC o LOCAL");
        panelHoraDiploma.add(lblHoraDiploma);
        panelHoraDiploma.add(cbHoraDiploma);
        GridBagConstraints gbcHora = new GridBagConstraints();
        gbcHora.gridx = 0;
        gbcHora.gridy = 5;   // fila propia, debajo de todas las casillas
        gbcHora.gridwidth = 2;
        gbcHora.anchor = GridBagConstraints.WEST;
        gbcHora.insets = new Insets(3, 4, 3, 8);
        panelVisibilidad.add(panelHoraDiploma, gbcHora);

        panelDerecho.add(panelVisibilidad);

        JPanel panelPropiedades = new JPanel(new GridBagLayout());
        panelPropiedades.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Propiedades del Campo", TitledBorder.LEFT, TitledBorder.TOP, new Font("Arial", Font.BOLD, 12)));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;

        lblCampoSeleccionado = new JLabel("Campo: Ninguno seleccionado");
        lblCampoSeleccionado.setFont(new Font("Arial", Font.BOLD, 13));
        lblCampoSeleccionado.setForeground(new Color(120, 180, 255));
        panelPropiedades.add(lblCampoSeleccionado, gbc);

        gbc.gridwidth = 1;
        gbc.gridy++;
        gbc.gridx = 0;
        panelPropiedades.add(new JLabel("Tipografía:"), gbc);
        cbFuente = new JComboBox<>(new String[]{"Helvetica", "Times-Roman", "Courier"});
        gbc.gridx = 1;
        panelPropiedades.add(cbFuente, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        panelPropiedades.add(new JLabel("Modo:"), gbc);
        cbEstilo = new JComboBox<>(new String[]{"Normal", "Negrita", "Cursiva", "Negrita-Cursiva"});
        gbc.gridx = 1;
        panelPropiedades.add(cbEstilo, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        panelPropiedades.add(new JLabel("Tamaño:"), gbc);
        spinTamano = new JSpinner(new SpinnerNumberModel(16, 6, 120, 1));
        gbc.gridx = 1;
        panelPropiedades.add(spinTamano, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        panelPropiedades.add(new JLabel("Color Texto:"), gbc);
        btnColorTexto = new JButton(" Elegir Color ");
        btnColorTexto.setFocusPainted(false);
        gbc.gridx = 1;
        panelPropiedades.add(btnColorTexto, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        panelPropiedades.add(new JLabel("Color Fondo:"), gbc);
        btnColorFondo = new JButton(" Elegir Color ");
        btnColorFondo.setFocusPainted(false);
        gbc.gridx = 1;
        panelPropiedades.add(btnColorFondo, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        JLabel lblTransp = new JLabel("Transparencia (%):");
        lblTransp.setToolTipText("0 = fondo sólido · 100 = sin fondo");
        panelPropiedades.add(lblTransp, gbc);
        spinTransparencia = new JSpinner(new SpinnerNumberModel(100, 0, 100, 5));
        spinTransparencia.setToolTipText("0 = fondo sólido · 100 = sin fondo");
        gbc.gridx = 1;
        panelPropiedades.add(spinTransparencia, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        panelPropiedades.add(new JLabel("Esquinas (px):"), gbc);
        spinRadio = new JSpinner(new SpinnerNumberModel(0, 0, 1000, 1));
        spinRadio.setToolTipText("0 = esquinas rectas. Cuanto más grande, más redondeadas");
        gbc.gridx = 1;
        panelPropiedades.add(spinRadio, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        panelPropiedades.add(new JLabel("Posición X (px):"), gbc);
        spinPosX = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 1));
        gbc.gridx = 1;
        panelPropiedades.add(spinPosX, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        panelPropiedades.add(new JLabel("Posición Y (px):"), gbc);
        spinPosY = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 1));
        gbc.gridx = 1;
        panelPropiedades.add(spinPosY, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        panelPropiedades.add(new JLabel("Ancho caja (px):"), gbc);
        spinAnchoCaja = new JSpinner(new SpinnerNumberModel(200, 10, 10000, 1));
        gbc.gridx = 1;
        panelPropiedades.add(spinAnchoCaja, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        panelPropiedades.add(new JLabel("Alto caja (px):"), gbc);
        spinAltoCaja = new JSpinner(new SpinnerNumberModel(40, 10, 10000, 1));
        gbc.gridx = 1;
        panelPropiedades.add(spinAltoCaja, gbc);

        panelDerecho.add(panelPropiedades);

        JPanel panelColores = new JPanel();
        panelColores.setLayout(new BoxLayout(panelColores, BoxLayout.Y_AXIS));
        panelColores.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Colores Banda / Modo", TitledBorder.LEFT, TitledBorder.TOP, new Font("Arial", Font.BOLD, 12)));
        panelColores.setMaximumSize(new Dimension(310, 120));

        // Ya no hay colores automáticos por banda/modo: cada campo usa su propio color (azul por defecto)
        chkColoresAutomaticos = new JCheckBox("Usar colores automáticos", false);
        chkColoresAutomaticos.setAlignmentX(Component.LEFT_ALIGNMENT);
        panelColores.add(chkColoresAutomaticos);
        panelColores.add(Box.createVerticalStrut(8));

        btnConfigurarColores = new JButton("Configurar colores Banda/Modo...");
        btnConfigurarColores.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnConfigurarColores.addActionListener(e -> mostrarDialogoColores());
        panelColores.add(btnConfigurarColores);

        panelDerecho.add(Box.createVerticalGlue());
        JScrollPane scrollDerecho = new JScrollPane(panelDerecho, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override public Dimension getPreferredSize() {
                // Siempre deja sitio para la barra, así el panel no se corta por la derecha cuando aparece
                return new Dimension(310 + getVerticalScrollBar().getPreferredSize().width, super.getPreferredSize().height);
            }
        };
        scrollDerecho.setBorder(null);
        scrollDerecho.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollDerecho, BorderLayout.EAST);

        cbFuente.addActionListener(ignored -> {
            if (actualizandoUI) return;
            String antes = (String) cbEstilo.getSelectedItem();
            String familia = (String) cbFuente.getSelectedItem();
            rellenarEstilos(familia, antes);
            String ahora = (String) cbEstilo.getSelectedItem();
            if (antes != null && !antes.equals(ahora))
                log("La tipografía «" + familia + "» no tiene «" + antes + "»: se usa «" + ahora + "».");
            aplicarCambiosDesdeUI();
        });
        cbEstilo.addActionListener(ignored -> aplicarCambiosDesdeUI());
        spinTamano.addChangeListener(ignored -> aplicarCambiosDesdeUI());
        spinPosX.addChangeListener(ignored -> aplicarCambiosDesdeUI());
        spinPosY.addChangeListener(ignored -> aplicarCambiosDesdeUI());
        spinAnchoCaja.addChangeListener(ignored -> aplicarCambiosDesdeUI());
        spinAltoCaja.addChangeListener(ignored -> aplicarCambiosDesdeUI());
        btnColorTexto.addActionListener(ignored -> seleccionarColorTexto());
        btnColorFondo.addActionListener(ignored -> seleccionarColorFondo());
        spinTransparencia.addChangeListener(ignored -> aplicarCambiosDesdeUI());
        spinRadio.addChangeListener(ignored -> aplicarCambiosDesdeUI());
        // Las fuentes instaladas en el ordenador se buscan en segundo plano (la ventana no espera)
        cargarFuentesDelSistemaEnSegundoPlano();

        panelDiseno.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                panelDiseno.recalcularEscala();
            }
        });

        txtLog = new JTextArea(4, 50);
        txtLog.setEditable(false);
        JScrollPane scrollLog = new JScrollPane(txtLog);
        scrollLog.setBorder(BorderFactory.createTitledBorder("Consola de Proceso"));
        add(scrollLog, BorderLayout.SOUTH);

        seleccionarCampo("CALL");
    }

    // ===================== CONFIGURACIÓN GUARDADA DEL DIPLOMA (por actividad) =====================
    private int numeralConfig = 0;

    /** Al abrir "Configurar Diploma": se recuerda la actividad y, si ya estaba configurado, se carga como se dejó. */
    public void prepararConfiguracion(int numeral) {
        numeralConfig = numeral;
        if (numeral <= 0) return;
        String cfg = LibroGuardiaUI.ConfigApp.get("DIPLOMA_CFG_" + numeral, "");
        if (!cfg.isEmpty()) aplicarConfiguracion(cfg);
        String modo = LibroGuardiaUI.ConfigApp.get("DIPLOMA_MODO_" + numeral, "");
        if ("AUTO".equals(modo)) log("Este diploma se crea automáticamente al añadir cada contacto (se guarda en: " + txtDestino.getText().trim() + ").");
        else if ("LOTE".equals(modo)) log("Este diploma se crea con el botón \"Generar Diplomas PDF\" (todos a la vez).");
    }

    private static String rgb(Color c) { return String.valueOf(c.getRGB()); }
    private static Color color(String v, Color def) {
        try { return v == null || v.isEmpty() ? def : new Color(Integer.parseInt(v), true); } catch (Exception e) { return def; }
    }
    private static int num(String v, int def) {
        try { return v == null || v.isEmpty() ? def : Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }

    /** Todo lo que se ha dejado configurado (plantilla, carpeta, campos, colores...) en un texto para guardarlo. */
    private String exportarConfiguracion() {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("img", txtImagen.getText().trim());
        p.setProperty("dest", txtDestino.getText().trim());
        p.setProperty("act", txtActivacion.getText().trim());
        p.setProperty("hora", String.valueOf(cbHoraDiploma.getSelectedItem()));
        p.setProperty("colAuto", chkColoresAutomaticos.isSelected() ? "1" : "0");
        for (Map.Entry<String, Color> e : coloresBanda.entrySet()) p.setProperty("colB." + e.getKey(), rgb(e.getValue()));
        for (Map.Entry<String, Color> e : coloresModo.entrySet()) p.setProperty("colM." + e.getKey(), rgb(e.getValue()));
        for (Map.Entry<String, PropiedadesCampo> e : camposMap.entrySet()) {
            String k = "f." + e.getKey() + ".";
            PropiedadesCampo c = e.getValue();
            p.setProperty(k + "vis", c.visible ? "1" : "0");
            p.setProperty(k + "fam", c.familiaFuente);
            p.setProperty(k + "est", c.estiloFuente);
            p.setProperty(k + "tam", String.valueOf(c.tamanoFuente));
            p.setProperty(k + "ct", rgb(c.colorTexto));
            p.setProperty(k + "x", String.valueOf(c.posXOriginal));
            p.setProperty(k + "y", String.valueOf(c.posYOriginal));
            p.setProperty(k + "w", String.valueOf(c.anchoCaja));
            p.setProperty(k + "h", String.valueOf(c.altoCaja));
            p.setProperty(k + "cf", rgb(c.colorFondo));
            p.setProperty(k + "tr", String.valueOf(c.transparenciaFondo));
            p.setProperty(k + "rad", String.valueOf(c.radioEsquinas));
        }
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            p.store(sw, null);
            return java.util.Base64.getEncoder().encodeToString(sw.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "";
        }
    }

    /** Deja la ventana como estaba configurada (lo contrario de exportarConfiguracion). */
    private void aplicarConfiguracion(String cfg) {
        java.util.Properties p = new java.util.Properties();
        try {
            p.load(new java.io.StringReader(new String(java.util.Base64.getDecoder().decode(cfg), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return;
        }
        String act = p.getProperty("act", "");
        if (!act.isEmpty()) txtActivacion.setText(act);
        String dest = p.getProperty("dest", "");
        if (!dest.isEmpty()) txtDestino.setText(dest);
        String hora = p.getProperty("hora", "");
        if (!hora.isEmpty()) cbHoraDiploma.setSelectedItem(hora);
        chkColoresAutomaticos.setSelected("1".equals(p.getProperty("colAuto", "0")));
        for (String k : new ArrayList<>(coloresBanda.keySet())) coloresBanda.put(k, color(p.getProperty("colB." + k), coloresBanda.get(k)));
        for (String k : new ArrayList<>(coloresModo.keySet())) coloresModo.put(k, color(p.getProperty("colM." + k), coloresModo.get(k)));
        for (Map.Entry<String, PropiedadesCampo> e : camposMap.entrySet()) {
            String k = "f." + e.getKey() + ".";
            if (p.getProperty(k + "x") == null) continue;
            PropiedadesCampo c = e.getValue();
            c.visible = !"0".equals(p.getProperty(k + "vis", "1"));
            c.familiaFuente = p.getProperty(k + "fam", c.familiaFuente);
            c.estiloFuente = p.getProperty(k + "est", c.estiloFuente);
            c.tamanoFuente = num(p.getProperty(k + "tam"), c.tamanoFuente);
            c.colorTexto = color(p.getProperty(k + "ct"), c.colorTexto);
            c.posXOriginal = num(p.getProperty(k + "x"), c.posXOriginal);
            c.posYOriginal = num(p.getProperty(k + "y"), c.posYOriginal);
            c.anchoCaja = num(p.getProperty(k + "w"), c.anchoCaja);
            c.altoCaja = num(p.getProperty(k + "h"), c.altoCaja);
            c.colorFondo = color(p.getProperty(k + "cf"), c.colorFondo);
            c.transparenciaFondo = num(p.getProperty(k + "tr"), c.transparenciaFondo);
            c.radioEsquinas = num(p.getProperty(k + "rad"), c.radioEsquinas);
            if (c.chkVisible != null) c.chkVisible.setSelected(c.visible);
            if (c.label != null) c.label.setVisible(c.visible);
        }
        String img = p.getProperty("img", "");
        // Si es la plantilla de Bitácora 26, se pone al día (la nueva trae FRECUENCIA) y se coloca la frecuencia en su hueco
        boolean deBitacora = new File(img).getName().equals("plantilla_diploma_bitacora26.jpg");
        if (deBitacora) plantillaBitacoraAlDia();
        if (!img.isEmpty() && new File(img).isFile()) {
            try {
                BufferedImage bi = ImageIO.read(new File(img));
                if (bi != null) {
                    txtImagen.setText(img);   // así no se pone encima la plantilla de ejemplo
                    panelDiseno.setImagenFondo(bi);
                    if (deBitacora && bi.getWidth() == 2480 && bi.getHeight() == 1754) ponerFrecuenciaEnPlantillaBitacora();
                }
            } catch (Exception ignored) { }
        }
        recalcularTodasLasEtiquetas();
        if (idCampoSeleccionado != null) seleccionarCampo(idCampoSeleccionado);
    }

    /** Botón Aceptar: guarda el diploma de la actividad y pregunta cómo se quieren crear los diplomas. */
    private void aceptarConfiguracion() {
        if (numeralConfig <= 0) {
            JOptionPane.showMessageDialog(this, "Abre esta ventana desde una actividad (botón Configurar Diploma) para poder guardarla.",
                    "Configurar Diploma", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (txtImagen.getText().trim().isEmpty() || txtDestino.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Elige la plantilla y la carpeta donde se guardarán los diplomas.", "Atención", JOptionPane.WARNING_MESSAGE);
            return;
        }
        LibroGuardiaUI.ConfigApp.put("DIPLOMA_CFG_" + numeralConfig, exportarConfiguracion());
        Object[] opciones = {"Automáticamente al añadir cada contacto", "Todos a la vez (Generar Diplomas PDF)"};
        String actual = LibroGuardiaUI.ConfigApp.get("DIPLOMA_MODO_" + numeralConfig, "");
        int r = JOptionPane.showOptionDialog(this,
                "El diploma de esta actividad ha quedado configurado.\n\n"
                        + "¿Cómo quieres crear los diplomas?\n\n"
                        + "• Automáticamente: al guardar un contacto en esta actividad se crea su diploma en PDF\n"
                        + "   (uno por indicativo: si ya tiene diploma, no se repite).\n"
                        + "• Todos a la vez: cuando quieras, con el botón \"Generar Diplomas PDF\" de esta ventana.",
                "Configurar Diploma", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opciones,
                "AUTO".equals(actual) ? opciones[0] : opciones[1]);
        if (r < 0) return;   // cerrada sin elegir: la configuración sí queda guardada
        boolean auto = r == 0;
        LibroGuardiaUI.ConfigApp.put("DIPLOMA_MODO_" + numeralConfig, auto ? "AUTO" : "LOTE");
        if (auto) {
            int g = JOptionPane.showConfirmDialog(this, "A partir de ahora el diploma se creará al añadir cada contacto.\n\n"
                            + "¿Quieres crear también ahora los diplomas de los contactos que ya tiene la actividad?",
                    "Configurar Diploma", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (g == JOptionPane.YES_OPTION) { procesarDiplomas(); return; }
        } else {
            JOptionPane.showMessageDialog(this, "Configuración guardada. Cuando quieras crear los diplomas, abre Configurar Diploma\n"
                    + "y pulsa \"Generar Diplomas PDF\".", "Configurar Diploma", JOptionPane.INFORMATION_MESSAGE);
        }
        dispose();
    }

    /**
     * Crea el diploma de un contacto recién añadido, con la configuración guardada de la actividad (sin abrir ventanas).
     * Uno por indicativo: si ya existe el PDF de ese indicativo, no se hace otra vez.
     */
    public static void generarAutomatico(int numeral, String actividadVisible, Map<String, String> qso, java.util.function.Consumer<String> aviso) {
        String cfg = LibroGuardiaUI.ConfigApp.get("DIPLOMA_CFG_" + numeral, "");
        if (cfg.isEmpty() || qso.getOrDefault("CALL", "").isEmpty()) return;
        SwingUtilities.invokeLater(() -> {
            GeneradorDiplomaUI g;
            try {
                g = new GeneradorDiplomaUI(actividadVisible, null);
                g.numeralConfig = numeral;
                g.aplicarConfiguracion(cfg);
            } catch (Exception ex) {
                aviso.accept("No se pudo preparar el diploma: " + ex.getMessage());
                return;
            }
            final GeneradorDiplomaUI gen = g;
            String activacion = gen.txtActivacion.getText().trim();
            String rutaImagen = gen.txtImagen.getText().trim();
            String destino = gen.txtDestino.getText().trim();
            String call = qso.get("CALL");
            Thread t = new Thread(() -> {
                try {
                    if (rutaImagen.isEmpty() || !new File(rutaImagen).isFile() || destino.isEmpty()) {
                        aviso.accept("Diploma de " + call + " no creado: falta la plantilla o la carpeta (Configurar Diploma).");
                        return;
                    }
                    new File(destino).mkdirs();
                    String actL = activacion.replaceAll("[\\\\/:*?\"<>|]", "_");
                    String callL = call.replaceAll("[\\\\/:*?\"<>|]", "_");
                    File pdf = new File(destino, !actL.isEmpty() ? callL + " - " + actL + ".pdf" : callL + ".pdf");
                    if (pdf.exists()) return;   // ese indicativo ya tiene su diploma
                    String problema = gen.comprobarTipografias(java.util.List.of(qso));
                    if (problema != null) {
                        aviso.accept("Diploma de " + call + " no creado: " + problema.replace("\n", " "));
                        return;
                    }
                    gen.generarPdfDiploma(qso, activacion, rutaImagen, destino);
                    aviso.accept("Diploma de " + call + " creado en " + destino);
                } catch (Exception ex) {
                    aviso.accept("No se pudo crear el diploma de " + call + ": " + ex.getMessage());
                } finally {
                    SwingUtilities.invokeLater(gen::dispose);
                }
            }, "DiplomaAutomatico");
            t.setDaemon(true);
            t.start();
        });
    }

    /**
     * Botón derecho en el log de una actividad → "Descargar el diploma de …": se crea el diploma de ese contacto
     * con la configuración guardada de la actividad (en una carpeta aparte, sin tocar los de siempre) y se guarda donde se elija.
     */
    public static void descargar(Component padre, int numeral, String actividadVisible, Map<String, String> qso) {
        String cfg = LibroGuardiaUI.ConfigApp.get("DIPLOMA_CFG_" + numeral, "");
        String call = qso.getOrDefault("CALL", "");
        if (cfg.isEmpty()) {
            JOptionPane.showMessageDialog(padre, "Esta actividad todavía no tiene diploma.\nPrepáralo antes en «Configurar Diploma».",
                    "Descargar diploma", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        GeneradorDiplomaUI gen;
        try {
            gen = new GeneradorDiplomaUI(actividadVisible, null);
            gen.numeralConfig = numeral;
            gen.aplicarConfiguracion(cfg);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(padre, "No se ha podido preparar el diploma: " + ex.getMessage(), "Descargar diploma", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String activacion = gen.txtActivacion.getText().trim(), rutaImagen = gen.txtImagen.getText().trim();
        if (rutaImagen.isEmpty() || !new File(rutaImagen).isFile()) {
            gen.dispose();
            JOptionPane.showMessageDialog(padre, "Falta la plantilla del diploma (Configurar Diploma).", "Descargar diploma", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String problema = gen.comprobarTipografias(java.util.List.of(qso));
        if (problema != null) {
            gen.dispose();
            JOptionPane.showMessageDialog(padre, problema, "Descargar diploma", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File tmp;
        File pdf;
        try {
            tmp = java.nio.file.Files.createTempDirectory("diploma").toFile();
            gen.enviarPorBitacora = false;
            gen.generarPdfDiploma(qso, activacion, rutaImagen, tmp.getAbsolutePath());
            File[] fs = tmp.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"));
            if (fs == null || fs.length == 0) throw new IOException("no se ha creado el PDF");
            pdf = fs[0];
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(padre, "No se ha podido crear el diploma de " + call + ": " + ex.getMessage(), "Descargar diploma", JOptionPane.WARNING_MESSAGE);
            return;
        } finally {
            gen.dispose();
        }
        File carpeta = new File(System.getProperty("user.home"), "Documents");
        JFileChooser fc = new JFileChooser(carpeta);
        fc.setDialogTitle("Guardar el diploma de " + call);
        fc.setSelectedFile(new File(carpeta, pdf.getName()));
        if (fc.showSaveDialog(padre) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) f = new File(f.getParentFile(), f.getName() + ".pdf");
        try {
            java.nio.file.Files.copy(pdf.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            pdf.delete();
            tmp.delete();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(padre, "No se ha podido guardar: " + ex.getMessage(), "Descargar diploma", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(padre, "Diploma guardado en:\n" + f.getAbsolutePath() + "\n\n¿Quieres abrir la carpeta?", "Descargar diploma",
                JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE) == JOptionPane.YES_OPTION) {
            try { Desktop.getDesktop().open(f.getParentFile()); } catch (Exception ignored) { }
        }
    }

    private void mostrarDialogoColores() {
        JDialog dialog = new JDialog(this, "Configurar colores de Banda y Modo", true);
        dialog.setSize(480, 520);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));

        JTabbedPane tabs = new JTabbedPane();

        JPanel panelBandas = new JPanel(new GridBagLayout());
        panelBandas.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        Map<String, JButton> botonesBanda = new LinkedHashMap<>();
        int row = 0;
        for (Map.Entry<String, Color> entry : coloresBanda.entrySet()) {
            String clave = entry.getKey();
            Color color = entry.getValue();
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0.4;
            panelBandas.add(new JLabel(clave), gbc);

            JButton btn = new JButton("  ");
            btn.setBackground(color);
            btn.setOpaque(true);
            btn.setBorderPainted(false);
            btn.setPreferredSize(new Dimension(80, 26));
            btn.addActionListener(e -> {
                Color nuevo = LibroGuardiaUI.elegirColor(dialog, "Color para " + clave, btn.getBackground());
                if (nuevo != null) btn.setBackground(nuevo);
            });
            botonesBanda.put(clave, btn);
            gbc.gridx = 1;
            gbc.weightx = 0.6;
            panelBandas.add(btn, gbc);
            row++;
        }

        JPanel panelModos = new JPanel(new GridBagLayout());
        panelModos.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        Map<String, JButton> botonesModo = new LinkedHashMap<>();
        row = 0;
        for (Map.Entry<String, Color> entry : coloresModo.entrySet()) {
            String clave = entry.getKey();
            Color color = entry.getValue();
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0.4;
            panelModos.add(new JLabel(clave), gbc);

            JButton btn = new JButton("  ");
            btn.setBackground(color);
            btn.setOpaque(true);
            btn.setBorderPainted(false);
            btn.setPreferredSize(new Dimension(80, 26));
            btn.addActionListener(e -> {
                Color nuevo = LibroGuardiaUI.elegirColor(dialog, "Color para " + clave, btn.getBackground());
                if (nuevo != null) btn.setBackground(nuevo);
            });
            botonesModo.put(clave, btn);
            gbc.gridx = 1;
            gbc.weightx = 0.6;
            panelModos.add(btn, gbc);
            row++;
        }

        tabs.addTab("Bandas", panelBandas);
        tabs.addTab("Modos", panelModos);
        dialog.add(tabs, BorderLayout.CENTER);

        JPanel panelBotones = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnAceptar = new JButton("Aceptar");
        JButton btnCancelar = new JButton("Cancelar");
        JButton btnRestablecer = new JButton("Restablecer valores por defecto");

        btnAceptar.addActionListener(e -> {
            for (Map.Entry<String, JButton> entry : botonesBanda.entrySet()) {
                coloresBanda.put(entry.getKey(), entry.getValue().getBackground());
            }
            for (Map.Entry<String, JButton> entry : botonesModo.entrySet()) {
                coloresModo.put(entry.getKey(), entry.getValue().getBackground());
            }
            dialog.dispose();
            log("Colores de Banda y Modo actualizados.");
        });

        btnCancelar.addActionListener(e -> dialog.dispose());
        panelBotones.add(btnRestablecer);
        panelBotones.add(btnCancelar);
        panelBotones.add(btnAceptar);
        dialog.add(panelBotones, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void crearCampoConVisibilidad(JPanel panelVisibilidad, String id, String etiquetaCheck, String textoMuestra,
                                          int x, int y, String estilo, int tamano, Color color, int columnaGrid, int filaGrid) {
        EtiquetaCampo label = new EtiquetaCampo(textoMuestra);

        PropiedadesCampo prop = new PropiedadesCampo();
        label.prop = prop;
        prop.label = label;
        prop.familiaFuente = "Helvetica";
        prop.estiloFuente = estilo;
        prop.tamanoFuente = tamano;
        prop.colorTexto = color;
        prop.posXOriginal = x;
        prop.posYOriginal = y;
        prop.visible = true;

        switch (id) {
            case "CALL" -> { prop.anchoCaja = 260; prop.altoCaja = 50; }
            case "QRA" -> { prop.anchoCaja = 260; prop.altoCaja = 40; }
            case "DATE" -> { prop.anchoCaja = 180; prop.altoCaja = 40; }
            case "TIME" -> { prop.anchoCaja = 180; prop.altoCaja = 40; }
            case "BAND" -> { prop.anchoCaja = 120; prop.altoCaja = 40; }
            case "MODE" -> { prop.anchoCaja = 120; prop.altoCaja = 40; }
            case "QTH" -> { prop.anchoCaja = 260; prop.altoCaja = 40; }
            case "RST_SENT" -> { prop.anchoCaja = 100; prop.altoCaja = 40; }
            case "RST_RCVD" -> { prop.anchoCaja = 100; prop.altoCaja = 40; }
            case "FREQ" -> { prop.anchoCaja = 160; prop.altoCaja = 40; }
            default -> { prop.anchoCaja = 200; prop.altoCaja = 40; }
        }

        ControladorArrastre cd = new ControladorArrastre(id);
        label.addMouseListener(cd);
        label.addMouseMotionListener(cd);

        camposMap.put(id, prop);
        panelDiseno.add(label);

        // Casilla = se ve o no en el diploma.  Nombre = pinchar para seleccionar el campo en la vista previa
        JCheckBox chk = new JCheckBox("", true);
        chk.setFocusPainted(false);
        chk.setToolTipText("Mostrar u ocultar " + etiquetaCheck + " en el diploma");
        prop.chkVisible = chk;
        JLabel nombre = new JLabel(etiquetaCheck);
        nombre.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        nombre.setToolTipText("Pincha para seleccionar " + etiquetaCheck + " en la vista previa");
        nombresCampo.put(id, nombre);
        nombre.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { seleccionarCampo(id); }
        });

        chk.addActionListener(e -> {
            prop.visible = chk.isSelected();
            label.setVisible(prop.visible);
            seleccionarCampo(id);
            panelDiseno.repaint();
        });

        JPanel fila = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fila.setOpaque(false);
        fila.add(chk);
        fila.add(nombre);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = columnaGrid;
        gbc.gridy = filaGrid;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(3, 8, 3, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panelVisibilidad.add(fila, gbc);
    }

    private void seleccionarCampo(String id) {
        this.idCampoSeleccionado = id;
        PropiedadesCampo prop = camposMap.get(id);
        if (prop == null) return;

        actualizandoUI = true;
        lblCampoSeleccionado.setText("Campo: " + id);
        cbFuente.setSelectedItem(prop.familiaFuente);
        rellenarEstilos(prop.familiaFuente, prop.estiloFuente);
        if (!prop.estiloFuente.equals(cbEstilo.getSelectedItem())) prop.estiloFuente = (String) cbEstilo.getSelectedItem();
        spinTamano.setValue(prop.tamanoFuente);
        btnColorTexto.setBackground(prop.colorTexto);
        btnColorTexto.setForeground(obtenerColorTextoContraste(prop.colorTexto));
        btnColorFondo.setBackground(prop.colorFondo);
        btnColorFondo.setForeground(obtenerColorTextoContraste(prop.colorFondo));
        spinTransparencia.setValue(prop.transparenciaFondo);
        spinRadio.setValue(prop.radioEsquinas);
        spinPosX.setValue(prop.posXOriginal);
        spinPosY.setValue(prop.posYOriginal);
        if (spinAnchoCaja != null) spinAnchoCaja.setValue(prop.anchoCaja);
        if (spinAltoCaja != null) spinAltoCaja.setValue(prop.altoCaja);
        actualizandoUI = false;

        panelDiseno.repaint();
        for (Map.Entry<String, PropiedadesCampo> entry : camposMap.entrySet()) {
            boolean esSeleccionado = entry.getKey().equals(id);
            if (esSeleccionado) {
                entry.getValue().label.setBorder(new BordeConNodos());
            } else {
                entry.getValue().label.setBorder(BorderFactory.createEmptyBorder());
            }
            JLabel n = nombresCampo.get(entry.getKey());
            if (n != null) {
                n.setForeground(esSeleccionado ? LibroGuardiaUI.TEMA_AMBAR : UIManager.getColor("CheckBox.foreground"));
                n.setFont(n.getFont().deriveFont(esSeleccionado ? Font.BOLD : Font.PLAIN));
            }
        }
    }

    private void seleccionarColorTexto() {
        if (idCampoSeleccionado == null) return;
        PropiedadesCampo prop = camposMap.get(idCampoSeleccionado);
        if (prop == null) return;

        Color nuevoColor = LibroGuardiaUI.elegirColor(this, "Selecciona el Color del Texto", prop.colorTexto);
        if (nuevoColor != null) {
            prop.colorTexto = nuevoColor;
            btnColorTexto.setBackground(nuevoColor);
            btnColorTexto.setForeground(obtenerColorTextoContraste(nuevoColor));
            aplicarCambiosDesdeUI();
        }
    }

    /** En "Modo" solo salen los estilos que tiene la tipografía (las tres de siempre los tienen todos). */
    private void rellenarEstilos(String familia, String preferido) {
        String[] todos = {"Normal", "Negrita", "Cursiva", "Negrita-Cursiva"};
        java.util.List<String> hay = new ArrayList<>();
        for (String e : todos) {
            boolean estandar = familia == null || familia.equals("Helvetica") || familia.equals("Times-Roman") || familia.equals("Courier");
            if (estandar || LibroGuardiaUI.FuentesSistema.fuenteAwt(familia, "Normal") == null || LibroGuardiaUI.FuentesSistema.archivo(familia, e, false) != null) hay.add(e);
        }
        if (hay.isEmpty()) hay.add("Normal");
        boolean antes = actualizandoUI;
        actualizandoUI = true;
        cbEstilo.setModel(new DefaultComboBoxModel<>(hay.toArray(new String[0])));
        cbEstilo.setSelectedItem(hay.contains(preferido) ? preferido : hay.get(0));
        cbEstilo.setToolTipText(hay.size() < 4 ? "La tipografía «" + familia + "» solo tiene: " + String.join(", ", hay) : null);
        actualizandoUI = antes;
    }

    private void seleccionarColorFondo() {
        if (idCampoSeleccionado == null) return;
        PropiedadesCampo prop = camposMap.get(idCampoSeleccionado);
        if (prop == null) return;
        Color nuevoColor = LibroGuardiaUI.elegirColor(this, "Selecciona el Color del Fondo", prop.colorFondo);
        if (nuevoColor != null) {
            prop.colorFondo = nuevoColor;
            btnColorFondo.setBackground(nuevoColor);
            btnColorFondo.setForeground(obtenerColorTextoContraste(nuevoColor));
            // Si el fondo estaba invisible, se pone medio transparente para que se vea el cambio
            if (prop.transparenciaFondo >= 100) spinTransparencia.setValue(30);
            aplicarCambiosDesdeUI();
        }
    }

    private Color obtenerColorTextoContraste(Color bg) {
        double brillo = (bg.getRed() * 0.299 + bg.getGreen() * 0.587 + bg.getBlue() * 0.114);
        return brillo > 128 ? Color.BLACK : Color.WHITE;
    }

    private void aplicarCambiosDesdeUI() {
        if (actualizandoUI || idCampoSeleccionado == null) return;
        PropiedadesCampo prop = camposMap.get(idCampoSeleccionado);
        if (prop == null) return;

        prop.familiaFuente = (String) cbFuente.getSelectedItem();
        prop.estiloFuente = (String) cbEstilo.getSelectedItem();
        prop.tamanoFuente = (Integer) spinTamano.getValue();
        prop.posXOriginal = (Integer) spinPosX.getValue();
        prop.posYOriginal = (Integer) spinPosY.getValue();
        if (spinAnchoCaja != null) prop.anchoCaja = (Integer) spinAnchoCaja.getValue();
        if (spinAltoCaja != null) prop.altoCaja = (Integer) spinAltoCaja.getValue();
        prop.transparenciaFondo = (Integer) spinTransparencia.getValue();
        prop.radioEsquinas = (Integer) spinRadio.getValue();

        actualizarEstiloVisualEtiqueta(prop);
        recalcularPosicionEtiquetaVisual(prop);
    }

    private void recalcularPosicionEtiquetaVisual(PropiedadesCampo prop) {
        double escala = panelDiseno.getEscala();
        int imgX = panelDiseno.getImgX();
        int imgY = panelDiseno.getImgY();

        actualizarEstiloVisualEtiqueta(prop);

        int posX = imgX + (int) Math.round(prop.posXOriginal * escala);
        int posY = imgY + (int) Math.round(prop.posYOriginal * escala);
        int ancho = (int) Math.round(prop.anchoCaja * escala);
        int alto = (int) Math.round(prop.altoCaja * escala);

        prop.label.setHorizontalAlignment(SwingConstants.CENTER);
        prop.label.setVerticalAlignment(SwingConstants.CENTER);
        prop.label.setBounds(posX, posY, ancho, alto);

        panelDiseno.repaint();
    }

    public void recalcularTodasLasEtiquetas() {
        for (PropiedadesCampo prop : camposMap.values()) {
            recalcularPosicionEtiquetaVisual(prop);
        }
    }

    private void actualizarEstiloVisualEtiqueta(PropiedadesCampo prop) {
        int fontStyle = Font.PLAIN;
        if ("Negrita".equals(prop.estiloFuente)) fontStyle = Font.BOLD;
        else if ("Cursiva".equals(prop.estiloFuente)) fontStyle = Font.ITALIC;
        else if ("Negrita-Cursiva".equals(prop.estiloFuente)) fontStyle = Font.BOLD | Font.ITALIC;

        String fontName = switch (prop.familiaFuente) {
            case "Times-Roman" -> "Serif";
            case "Courier" -> "Monospaced";
            default -> "SansSerif";
        };

        double escala = panelDiseno.getEscala();
        int tamanoEscalado = Math.max(8, (int) Math.round(prop.tamanoFuente * escala));

        Font delSistema = LibroGuardiaUI.FuentesSistema.fuenteAwt(prop.familiaFuente, prop.estiloFuente);
        if (delSistema != null) {
            // Fuente instalada en el ordenador: si no tiene ese estilo, Java la imita (en el PDF se usa la normal)
            boolean tieneEstilo = LibroGuardiaUI.FuentesSistema.archivo(prop.familiaFuente, prop.estiloFuente, false) != null;
            prop.label.setFont(delSistema.deriveFont(tieneEstilo ? Font.PLAIN : fontStyle, (float) tamanoEscalado));
        } else {
            prop.label.setFont(new Font(fontName, fontStyle, tamanoEscalado));
        }
        prop.label.setForeground(prop.colorTexto);
        prop.label.setHorizontalAlignment(SwingConstants.CENTER);
        prop.label.setVerticalAlignment(SwingConstants.CENTER);
    }

    private Color obtenerColorBanda(String band) {
        if (band == null || !chkColoresAutomaticos.isSelected()) return null;
        String b = band.trim().toUpperCase().replace(" ", "");
        if (b.equals("160") || b.equals("160M")) return coloresBanda.getOrDefault("160M", Color.DARK_GRAY);
        if (b.equals("80") || b.equals("80M")) return coloresBanda.getOrDefault("80M", Color.DARK_GRAY);
        if (b.equals("40") || b.equals("40M")) return coloresBanda.getOrDefault("40M", Color.DARK_GRAY);
        if (b.equals("20") || b.equals("20M")) return coloresBanda.getOrDefault("20M", Color.DARK_GRAY);
        if (b.equals("15") || b.equals("15M")) return coloresBanda.getOrDefault("15M", Color.DARK_GRAY);
        if (b.equals("10") || b.equals("10M")) return coloresBanda.getOrDefault("10M", Color.DARK_GRAY);
        if (b.equals("6") || b.equals("6M")) return coloresBanda.getOrDefault("6M", Color.DARK_GRAY);
        if (b.equals("2") || b.equals("2M")) return coloresBanda.getOrDefault("2M", Color.DARK_GRAY);
        if (b.contains("70")) return coloresBanda.getOrDefault("70CM", Color.DARK_GRAY);
        return Color.DARK_GRAY;
    }

    private Color obtenerColorModo(String mode) {
        if (mode == null || !chkColoresAutomaticos.isSelected()) return null;
        String m = mode.trim().toUpperCase();
        if (m.equals("SSB") || m.equals("USB") || m.equals("LSB"))
            return coloresModo.getOrDefault("SSB", Color.DARK_GRAY);
        if (m.equals("FM")) return coloresModo.getOrDefault("FM", Color.DARK_GRAY);
        if (m.equals("AM")) return coloresModo.getOrDefault("AM", Color.DARK_GRAY);
        if (m.equals("CW")) return coloresModo.getOrDefault("CW", Color.DARK_GRAY);
        return Color.DARK_GRAY;
    }

    /**
     * Plantilla de diploma que trae el programa (recurso plantilla_diploma.jpg). Se copia a Documentos\\Libro de Guardia
     * para poder generar los PDF con ella, se pone de fondo y se colocan los campos en su sitio. Solo si no hay otra elegida.
     */
    /** Busca las fuentes del ordenador (en segundo plano) y las añade al desplegable "Tipografía". */
    private void cargarFuentesDelSistemaEnSegundoPlano() {
        Thread t = new Thread(() -> {
            java.util.List<String> familias = LibroGuardiaUI.FuentesSistema.familias();
            SwingUtilities.invokeLater(() -> {
                boolean antes = actualizandoUI;
                actualizandoUI = true;
                Object sel = cbFuente.getSelectedItem();
                for (String fam : familias) if (((DefaultComboBoxModel<String>) cbFuente.getModel()).getIndexOf(fam) < 0) cbFuente.addItem(fam);
                cbFuente.setSelectedItem(sel);
                actualizandoUI = antes;
                if (!familias.isEmpty()) log("Tipografías: " + familias.size() + " fuentes del ordenador disponibles.");
            });
        }, "FuentesSistema");
        t.setDaemon(true);
        t.start();
        cbFuente.setMaximumRowCount(20);
        cbFuente.setPrototypeDisplayValue("Times-Roman Bold XX");   // nombres largos no ensanchan el panel
        cbFuente.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (index >= 0 && value != null) {   // en la lista, cada fuente se ve con su propia letra
                    Font f = LibroGuardiaUI.FuentesSistema.fuenteAwt(value.toString(), "Normal");
                    if (f != null) l.setFont(f.deriveFont(Font.PLAIN, 15f));
                }
                return l;
            }
        });
    }

    /** La plantilla de Bitácora 26 en Mis documentos, siempre la última versión (la nueva trae el hueco de FRECUENCIA). */
    private static File plantillaBitacoraAlDia() {
        try {
            File destino = new File(new File(LibroGuardiaUI.DatabaseManager.obtenerRutaActiva()).getParentFile(), "plantilla_diploma_bitacora26.jpg");
            byte[] buena;
            try (java.io.InputStream is = LibroGuardiaUI.class.getResourceAsStream("/plantilla_diploma.jpg")) {
                if (is == null) return destino.isFile() ? destino : null;
                buena = is.readAllBytes();
            }
            if (!destino.isFile() || destino.length() != buena.length) java.nio.file.Files.write(destino.toPath(), buena);
            return destino;
        } catch (Exception e) {
            return null;
        }
    }

    /** Diplomas ya configurados con la plantilla de Bitácora 26: el QTH pasa a la derecha y la frecuencia ocupa su hueco. */
    private void ponerFrecuenciaEnPlantillaBitacora() {
        PropiedadesCampo q = camposMap.get("QTH"), f = camposMap.get("FREQ");
        if (q == null || f == null) return;
        if (q.posXOriginal == 700 && q.posYOriginal == 1222 && q.anchoCaja == 1080) {   // el QTH estaba en su sitio de antes
            q.posXOriginal = 1240; q.anchoCaja = 640;
        }
        if (f.posYOriginal < 300) {   // la frecuencia seguía arriba, donde salía al principio
            f.posXOriginal = 660; f.posYOriginal = 1222; f.anchoCaja = 480; f.altoCaja = 62; f.tamanoFuente = 46;
            f.colorTexto = new Color(0, 51, 102);
            f.visible = true;
            if (f.chkVisible != null) f.chkVisible.setSelected(true);
            if (f.label != null) f.label.setVisible(true);
        }
    }

    private void cargarPlantillaPorDefecto() {
        if (!txtImagen.getText().trim().isEmpty()) return;
        try {
            File destino = plantillaBitacoraAlDia();
            if (destino == null) return;
            BufferedImage img = ImageIO.read(destino);
            if (img == null) return;
            txtImagen.setText(destino.getAbsolutePath());
            // Campos colocados sobre las líneas de la plantilla (en píxeles de la imagen: 2480 × 1754)
            Color azul = new Color(0, 51, 102);
            Object[][] sitio = {
                    {"CALL", 120, 740, 750, 1000, 150}, {"QRA", 60, 820, 915, 840, 80},
                    {"DATE", 46, 320, 1062, 270, 62}, {"TIME", 46, 680, 1062, 270, 62}, {"BAND", 46, 1000, 1062, 270, 62},
                    {"MODE", 46, 1260, 1062, 250, 62}, {"RST_SENT", 46, 1490, 1062, 270, 62}, {"RST_RCVD", 46, 1780, 1062, 270, 62},
                    {"FREQ", 46, 660, 1222, 480, 62}, {"QTH", 46, 1240, 1222, 640, 62}};
            if (img.getWidth() == 2480 && img.getHeight() == 1754) {
                for (Object[] c : sitio) {
                    PropiedadesCampo p = camposMap.get((String) c[0]);
                    if (p == null) continue;
                    if ("FREQ".equals(c[0])) {   // la plantilla ya tiene su hueco: se enseña
                        p.visible = true;
                        if (p.chkVisible != null) p.chkVisible.setSelected(true);
                        if (p.label != null) p.label.setVisible(true);
                    }
                    p.tamanoFuente = (Integer) c[1];
                    p.posXOriginal = (Integer) c[2];
                    p.posYOriginal = (Integer) c[3];
                    p.anchoCaja = (Integer) c[4];
                    p.altoCaja = (Integer) c[5];
                    p.colorTexto = azul;
                }
            }
            panelDiseno.setImagenFondo(img);
            if (idCampoSeleccionado != null) seleccionarCampo(idCampoSeleccionado);   // que se vean los valores nuevos
            log("Plantilla de Bitácora 26 cargada. Puedes elegir otra con \"Examinar...\".");
        } catch (Exception e) {
            System.err.println("No se pudo cargar la plantilla de diploma por defecto: " + e.getMessage());
        }
    }

    private void cargarImagenSeleccionada() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Imágenes (*.jpg, *.png)", "jpg", "jpeg", "png"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        String ruta = chooser.getSelectedFile().getAbsolutePath();

        BufferedImage img;
        try {
            img = ImageIO.read(new File(ruta));
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "No se pudo leer la imagen:\n" + e.getMessage(),
                    "Error de imagen", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (img == null) {
            JOptionPane.showMessageDialog(this,
                    "El archivo seleccionado no es una imagen válida.",
                    "Error de imagen", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int anchoPx = img.getWidth();
        int altoPx = img.getHeight();
        boolean horizontal = anchoPx >= altoPx;

        final double MM_LARGO = 297.0;

        double anchoMm, altoMm;
        if (horizontal) {
            anchoMm = MM_LARGO;
            altoMm = (double) altoPx * MM_LARGO / anchoPx;
        } else {
            altoMm = MM_LARGO;
            anchoMm = (double) anchoPx * MM_LARGO / altoPx;
        }

        double ladoLargoPx = horizontal ? anchoPx : altoPx;
        double pppEstimados = ladoLargoPx / (MM_LARGO / 25.4);

        String orientacion = horizontal ? "HORIZONTAL (A4 apaisado)" : "VERTICAL (A4 vertical)";
        String mensaje = String.format(java.util.Locale.US,
                "<html><b>Plantilla seleccionada:</b><br><br>" +
                        "• Píxeles: <b>%d × %d px</b><br>" +
                        "• Orientación: <b>%s</b><br>" +
                        "• Tamaño impreso: <b>%.1f × %.1f mm</b><br>" +
                        "• Resolución estimada: <b>%.0f ppp</b><br><br>" +
                        "<i>La imagen se ajustará al lado largo de un A4 (297 mm).<br>" +
                        "Si los ppp son muy bajos, podría verse borrosa al imprimir.</i><br><br>" +
                        "¿Cargar esta plantilla?</html>",
                anchoPx, altoPx, orientacion, anchoMm, altoMm, pppEstimados);

        int resp = JOptionPane.showConfirmDialog(this, mensaje,
                "Confirmar plantilla",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.INFORMATION_MESSAGE);

        if (resp != JOptionPane.OK_OPTION) {
            return;
        }

        txtImagen.setText(ruta);
        panelDiseno.setImagenFondo(img);
        log("Plantilla cargada: " + anchoPx + "x" + altoPx + " px | " +
                orientacion + " | " + String.format(java.util.Locale.US, "%.0f ppp", pppEstimados));
    }

    private void seleccionarArchivoLog(JTextField campo) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Archivos ADIF (*.adi)", "adi"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            campo.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void seleccionarCarpeta(JTextField campo) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            campo.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void log(String msj) {
        txtLog.append(msj + "\n");
        txtLog.setCaretPosition(txtLog.getDocument().getLength());
    }

    /**
     * Carpeta de diplomas para la web (iframe): la del radioclub si la actividad es del radioclub, o la del operador
     * si es suya. Devuelve la subcarpeta de la actividad, o null si no hay carpeta configurada.
     */
    static File carpetaWebDe(int numeral) {
        if (numeral <= 0) return null;
        try (java.sql.Connection c = LibroGuardiaUI.DatabaseManager.conectar();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT nombre, COALESCE(radioclub,0) FROM actividades WHERE numeral = ?")) {
            ps.setInt(1, numeral);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String raiz = rs.getInt(2) == 1 ? LibroGuardiaUI.ConfigApp.get("RC_CARPETA_DIPLOMAS", "")
                        : LibroGuardiaUI.ConfigApp.get("OP_CARPETA_DIPLOMAS_" + (LibroGuardiaUI.operadorActual == null ? "" : LibroGuardiaUI.operadorActual), "");
                if (raiz.isEmpty()) return null;
                return new File(raiz, subcarpetaWeb(rs.getString(1)));
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Nombre de la subcarpeta de una actividad (el que se ve, sin número ni caracteres no válidos). */
    static String subcarpetaWeb(String nombre) {
        String n = nombre == null ? "" : nombre;
        int i = n.lastIndexOf(" #");
        if (i > 0) n = n.substring(0, i);
        return n.replaceAll("^\\s*\\d+\\s*-\\s*", "").replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /** Copia del diploma en la carpeta de la web (Drive), si la actividad la tiene configurada. */
    private void copiarACarpetaWeb(File pdf) {
        if (!enviarPorBitacora) return;   // un diploma descargado a mano no se publica
        File dir = carpetaWebDe(numeralConfig);
        if (dir == null) return;
        try {
            dir.mkdirs();
            File destino = new File(dir, pdf.getName());
            if (!destino.getCanonicalPath().equals(pdf.getCanonicalPath()))
                java.nio.file.Files.copy(pdf.toPath(), destino.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logSeguro("No se ha podido copiar el diploma a la carpeta de la web: " + e.getMessage());
        }
    }

    /** false al descargar un diploma suelto (botón derecho): ese no se manda por Bitácora 26. */
    private boolean enviarPorBitacora = true;

    /** Igual que log, pero se puede llamar desde cualquier hilo (los envíos por Bitácora 26 van en segundo plano). */
    private void logSeguro(String msj) {
        SwingUtilities.invokeLater(() -> log(msj));
    }

    private void procesarDiplomas() {
        if (numeralConfig > 0) LibroGuardiaUI.ConfigApp.put("DIPLOMA_CFG_" + numeralConfig, exportarConfiguracion());
        String activacion = txtActivacion.getText().trim();
        String rutaAdi = txtAdi.getText().trim();
        String rutaImagen = txtImagen.getText().trim();
        String rutaDestino = txtDestino.getText().trim();

        if (rutaAdi.isEmpty() || rutaImagen.isEmpty() || rutaDestino.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Por favor completa los campos de Archivo LOG, Plantilla y Carpeta.", "Atención", JOptionPane.WARNING_MESSAGE);
            return;
        }

        new Thread(() -> {
            try {
                log("Leyendo archivo ADIF...");
                String content = Files.readString(Paths.get(rutaAdi));
                String[] parts = content.split("(?i)<eoh>");
                if (parts.length < 2) {
                    log("ERROR: Estructura ADIF inválida (no se encontró <eoh>).");
                    return;
                }

                String[] qsoRecords = parts[1].split("(?i)<eor>");
                Set<String> indicativosProcesados = new HashSet<>();
                int totalGenerados = 0;
                int totalDescartados = 0;
                java.util.List<Map<String, String>> aGenerar = new ArrayList<>();

                for (String qsoRecord : qsoRecords) {
                    if (qsoRecord.trim().isEmpty()) continue;
                    Map<String, String> datosQso = parseQsoRecord(qsoRecord);
                    String call = datosQso.getOrDefault("CALL", "").trim();
                    if (call.isEmpty()) continue;

                    if (indicativosProcesados.contains(call)) {
                        totalDescartados++;
                        continue;
                    }

                    indicativosProcesados.add(call);
                    aGenerar.add(datosQso);
                }

                // Si alguna tipografía da problemas no se genera ningún diploma y se explica el motivo
                String problema = comprobarTipografias(aGenerar);
                if (problema != null) {
                    log("NO SE HAN GENERADO LOS DIPLOMAS: " + problema.replace("\n", " "));
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                            "No se ha generado ningún diploma.\n\n" + problema, "Tipografía", JOptionPane.ERROR_MESSAGE));
                    return;
                }

                for (Map<String, String> datosQso : aGenerar) {
                    generarPdfDiploma(datosQso, activacion, rutaImagen, rutaDestino);
                    totalGenerados++;
                }

                log("--- ¡Proceso finalizado con éxito! ---");
                log("Total diplomas creados: " + totalGenerados);

                JOptionPane.showMessageDialog(this,
                        "Se han generado " + totalGenerados + " diplomas correctamente." +
                                (totalDescartados > 0 ? "\n(" + totalDescartados + " indicativos repetidos descartados)" : ""),
                        "Éxito", JOptionPane.INFORMATION_MESSAGE);
                // Al pulsar Aceptar se abre la carpeta donde se han guardado los diplomas
                try {
                    File carpeta = new File(rutaDestino);
                    if (carpeta.isDirectory() && Desktop.isDesktopSupported()) Desktop.getDesktop().open(carpeta);
                } catch (Exception exCarpeta) {
                    log("No se pudo abrir la carpeta: " + exCarpeta.getMessage());
                }

            } catch (Exception ex) {
                log("ERROR: " + ex.getMessage());
            }
        }).start();
    }

    /**
     * Antes de crear ningún diploma se comprueba que las tipografías del ordenador elegidas se pueden meter en el PDF
     * y que tienen todas las letras que hacen falta. Devuelve el motivo del problema, o null si está todo bien.
     */
    private String comprobarTipografias(java.util.List<Map<String, String>> qsos) {
        java.util.List<String> estandar = java.util.List.of("Helvetica", "Times-Roman", "Courier");
        Map<String, String> nombresCampo = Map.of("CALL", "Indicativo", "QRA", "QRA (Nombre)", "DATE", "Fecha", "TIME", "Hora",
                "BAND", "Banda", "QTH", "QTH", "MODE", "Modo", "RST_SENT", "RST Send", "RST_RCVD", "RST TX", "FREQ", "Frecuencia");
        try (PDDocument prueba = new PDDocument()) {
            Map<String, PDFont> cargadas = new HashMap<>();
            for (Map.Entry<String, PropiedadesCampo> en : camposMap.entrySet()) {
                PropiedadesCampo prop = en.getValue();
                if (!prop.visible || estandar.contains(prop.familiaFuente)) continue;
                String campo = nombresCampo.getOrDefault(en.getKey(), en.getKey());
                File f = LibroGuardiaUI.FuentesSistema.archivo(prop.familiaFuente, prop.estiloFuente, false);
                if (f == null) return "No se encuentra la tipografía «" + prop.familiaFuente + "» (" + prop.estiloFuente
                        + ") del campo " + campo + ".\n¿Se ha desinstalado del ordenador? Elige otra.";
                PDFont pf = cargadas.get(f.getAbsolutePath());
                if (pf == null) {
                    try {
                        pf = org.apache.pdfbox.pdmodel.font.PDType0Font.load(prueba, f);
                    } catch (Exception ex) {
                        return "La tipografía «" + prop.familiaFuente + "» del campo " + campo + " no se puede meter en el PDF.\n"
                                + "Motivo: " + motivoFuente(ex)
                                + "\n\nElige otra tipografía para ese campo.";
                    }
                    cargadas.put(f.getAbsolutePath(), pf);
                }
                for (Map<String, String> qso : qsos) {
                    String texto = textosDiploma(qso).get(en.getKey());
                    if (texto == null || texto.isEmpty()) continue;
                    for (int k = 0; k < texto.length(); ) {
                        int cp = texto.codePointAt(k);
                        String letra = new String(Character.toChars(cp));
                        try {
                            pf.encode(letra);
                        } catch (Exception sinLetra) {
                            return "La tipografía «" + prop.familiaFuente + "» del campo " + campo + " no tiene la letra «" + letra
                                    + "», que hace falta en el diploma de " + qso.getOrDefault("CALL", "?") + " («" + texto + "»).\n\n"
                                    + "Elige otra tipografía para ese campo.";
                        }
                        k += Character.charCount(cp);
                    }
                }
            }
        } catch (IOException ex) {
            return "No se pudieron comprobar las tipografías: " + ex.getMessage();
        }
        return null;
    }

    /** Explica en español por qué una fuente no se puede meter en el PDF. */
    private static String motivoFuente(Exception ex) {
        String m = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        String b = m.toLowerCase();
        if (b.contains("cff")) return "es una fuente OpenType de tipo CFF (.otf), que el generador de PDF no admite. Suele haber versión .ttf de la misma fuente.";
        if (b.contains("embed") || b.contains("permission") || b.contains("license")) return "el autor de la fuente no permite meterla en documentos (licencia).";
        return m;
    }

    private Map<String, String> parseQsoRecord(String record) {
        Map<String, String> fields = new HashMap<>();
        Pattern pattern = Pattern.compile("<(\\w+):(\\d+)(?::\\w+)?>([^<]+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(record);
        while (matcher.find()) {
            fields.put(matcher.group(1).toUpperCase(), matcher.group(3).trim());
        }
        return fields;
    }

    /** Lo que se escribe en cada campo del diploma para un contacto (fecha y hora ya formateadas). */
    private Map<String, String> textosDiploma(Map<String, String> qso) {
        String call = qso.getOrDefault("CALL", "DESCONOCIDO");
        String dateRaw = qso.getOrDefault("QSO_DATE", "");
        String timeRaw = qso.getOrDefault("TIME_ON", "");
        String band = qso.getOrDefault("BAND", "");
        String mode = qso.getOrDefault("MODE", "");
        String rstSent = qso.getOrDefault("RST_SENT", "59");
        String rstRcvd = qso.getOrDefault("RST_RCVD", "59");
        String qth = qso.getOrDefault("QTH", "");
        String qra = qso.getOrDefault("NAME", "");
        String freq = LibroGuardiaUI.frecuenciaParaMostrar(qso.getOrDefault("FREQ", ""));

        String dateFormatted = dateRaw.length() == 8
                ? dateRaw.substring(6, 8) + "/" + dateRaw.substring(4, 6) + "/" + dateRaw.substring(0, 4)
                : dateRaw;

        String timeFormatted = timeRaw.length() >= 4
                ? timeRaw.substring(0, 2) + ":" + timeRaw.substring(2, 4) + " UTC"
                : timeRaw;

        if ("LOCAL".equals(cbHoraDiploma.getSelectedItem()) && dateRaw.length() == 8 && timeRaw.length() >= 4) {
            try {
                java.time.LocalDateTime utc = java.time.LocalDateTime.of(
                        Integer.parseInt(dateRaw.substring(0, 4)), Integer.parseInt(dateRaw.substring(4, 6)),
                        Integer.parseInt(dateRaw.substring(6, 8)), Integer.parseInt(timeRaw.substring(0, 2)),
                        Integer.parseInt(timeRaw.substring(2, 4)));
                java.time.ZonedDateTime loc = utc.atZone(java.time.ZoneOffset.UTC)
                        .withZoneSameInstant(java.time.ZoneId.systemDefault());
                dateFormatted = loc.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                timeFormatted = loc.format(DateTimeFormatter.ofPattern("HH:mm")) + " LOCAL";
            } catch (Exception ignoredConv) {
                // si la fecha u hora no son válidas se deja el valor UTC original
            }
        }

        Map<String, String> t = new HashMap<>();
        t.put("CALL", call); t.put("DATE", dateFormatted); t.put("TIME", timeFormatted); t.put("BAND", band);
        t.put("MODE", mode); t.put("RST_SENT", rstSent); t.put("RST_RCVD", rstRcvd); t.put("QTH", qth); t.put("QRA", qra);
        t.put("FREQ", freq);
        return t;
    }

    private void generarPdfDiploma(Map<String, String> qso, String activacion, String rutaPlantilla, String carpetaSalida) throws IOException {
        Map<String, String> textos = textosDiploma(qso);
        String call = textos.get("CALL");

        String activacionLimpia = activacion.replaceAll("[\\\\/:*?\"<>|]", "_");
        String callLimpio = call.replaceAll("[\\\\/:*?\"<>|]", "_");

        String nombreArchivo = !activacionLimpia.isEmpty() ? callLimpio + " - " + activacionLimpia + ".pdf" : callLimpio + ".pdf";
        String archivoDestino = carpetaSalida + File.separator + nombreArchivo;

        try (PDDocument document = new PDDocument()) {
            documentoActual = document;
            fuentesCargadas.clear();
            PDImageXObject image = PDImageXObject.createFromFile(rutaPlantilla, document);
            float imgAnchoPx = image.getWidth();
            float imgAltoPx = image.getHeight();

            boolean horizontal = imgAnchoPx >= imgAltoPx;

            final float MM_A_PT = 72f / 25.4f;
            final float LADO_LARGO_PT = 297f * MM_A_PT;
            final float LADO_CORTO_PT = 210f * MM_A_PT;

            float pageWidth = horizontal ? LADO_LARGO_PT : LADO_CORTO_PT;
            float pageHeight = horizontal ? LADO_CORTO_PT : LADO_LARGO_PT;

            PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
            document.addPage(page);

            float ladoLargoPx = horizontal ? imgAnchoPx : imgAltoPx;
            float escala = LADO_LARGO_PT / ladoLargoPx;

            float imgAnchoPt = imgAnchoPx * escala;
            float imgAltoPt = imgAltoPx * escala;

            float imgX = (pageWidth - imgAnchoPt) / 2f;
            float imgY = (pageHeight - imgAltoPt) / 2f;

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.drawImage(image, imgX, imgY, imgAnchoPt, imgAltoPt);

                float[] ctx = new float[]{imgX, imgY, imgAltoPx, escala};

                escribirCampoPDF(contentStream, textos.get("CALL"), "CALL", ctx, null);
                escribirCampoPDF(contentStream, textos.get("DATE"), "DATE", ctx, null);
                escribirCampoPDF(contentStream, textos.get("TIME"), "TIME", ctx, null);
                escribirCampoPDF(contentStream, textos.get("BAND"), "BAND", ctx, obtenerColorBanda(textos.get("BAND")));
                escribirCampoPDF(contentStream, textos.get("MODE"), "MODE", ctx, obtenerColorModo(textos.get("MODE")));
                escribirCampoPDF(contentStream, textos.get("RST_SENT"), "RST_SENT", ctx, null);
                escribirCampoPDF(contentStream, textos.get("RST_RCVD"), "RST_RCVD", ctx, null);
                escribirCampoPDF(contentStream, textos.get("QTH"), "QTH", ctx, null);
                escribirCampoPDF(contentStream, textos.get("QRA"), "QRA", ctx, null);
                escribirCampoPDF(contentStream, textos.get("FREQ"), "FREQ", ctx, null);
            }

            document.save(archivoDestino);
            copiarACarpetaWeb(new File(archivoDestino));
            log("Generado: " + nombreArchivo);
        }
        // Si el contacto usa Bitácora 26, se le manda también por aquí (en segundo plano)
        LibroGuardiaUI ui = CuentaBitacora.instanciaB26;
        if (ui != null && enviarPorBitacora) ui.cuentaB26.enviarDiploma(call, qso.getOrDefault("BAND", ""), activacion, new File(archivoDestino), this::logSeguro);
    }

    private void escribirCampoPDF(PDPageContentStream stream, String texto, String fieldKey, float[] ctx, Color colorForzado) throws IOException {
        PropiedadesCampo prop = camposMap.get(fieldKey);
        if (prop == null || !prop.visible || texto == null || texto.isEmpty()) return;

        float imgX = ctx[0];
        float imgY = ctx[1];
        float imgAltoPx = ctx[2];
        float escala = ctx[3];

        PDFont fuentePDF = obtenerFuentePDF(prop.familiaFuente, prop.estiloFuente);

        float cajaAnchoPt = prop.anchoCaja * escala;
        float cajaAltoPt = prop.altoCaja * escala;

        float cajaX = imgX + prop.posXOriginal * escala;
        float cajaYTop = imgY + (imgAltoPx - prop.posYOriginal) * escala;
        float cajaYBottom = cajaYTop - cajaAltoPt;

        float fontSizeMax = prop.tamanoFuente * escala;
        float fontSize = fontSizeMax;
        float anchoTexto = medirAnchoTexto(fuentePDF, texto, fontSize);

        final float MIN_FONT = 6f;
        while (anchoTexto > cajaAnchoPt && fontSize > MIN_FONT) {
            fontSize -= 0.5f;
            anchoTexto = medirAnchoTexto(fuentePDF, texto, fontSize);
        }

        // Fondo del campo (color, transparencia y esquinas redondeadas)
        if (prop.transparenciaFondo < 100) {
            float alfa = (100 - prop.transparenciaFondo) / 100f;
            org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState gs = new org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(alfa);
            stream.saveGraphicsState();
            stream.setGraphicsStateParameters(gs);
            Color cf = prop.colorFondo;
            stream.setNonStrokingColor(cf.getRed() / 255f, cf.getGreen() / 255f, cf.getBlue() / 255f);
            float rr = Math.min(prop.radioEsquinas * escala, Math.min(cajaAnchoPt, cajaAltoPt) / 2f);
            rectanguloRedondeado(stream, cajaX, cajaYBottom, cajaAnchoPt, cajaAltoPt, rr);
            stream.fill();
            stream.restoreGraphicsState();
        }

        float inicioX = cajaX + (cajaAnchoPt - anchoTexto) / 2f;
        float capHeight = fuentePDF.getFontDescriptor().getCapHeight() / 1000f * fontSize;
        float centroY = cajaYBottom + cajaAltoPt / 2f;
        float baselineY = centroY - capHeight / 2f;

        stream.beginText();
        stream.setFont(fuentePDF, fontSize);

        Color c = (colorForzado != null) ? colorForzado : prop.colorTexto;
        stream.setNonStrokingColor(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f);

        stream.newLineAtOffset(inicioX, baselineY);
        try {
            stream.showText(texto);
        } catch (IllegalArgumentException sinLetra) {
            throw new IOException("la tipografía «" + prop.familiaFuente + "» no puede escribir «" + texto + "» (" + sinLetra.getMessage() + ")");
        }
        stream.endText();
    }

    /** Trazado de un rectángulo con las esquinas redondeadas (radio 0 = rectángulo normal). */
    private static void rectanguloRedondeado(PDPageContentStream s, float x, float y, float w, float h, float r) throws IOException {
        if (r <= 0.1f) { s.addRect(x, y, w, h); return; }
        float k = 0.5523f * r;
        s.moveTo(x + r, y);
        s.lineTo(x + w - r, y);
        s.curveTo(x + w - r + k, y, x + w, y + r - k, x + w, y + r);
        s.lineTo(x + w, y + h - r);
        s.curveTo(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h);
        s.lineTo(x + r, y + h);
        s.curveTo(x + r - k, y + h, x, y + h - r + k, x, y + h - r);
        s.lineTo(x, y + r);
        s.curveTo(x, y + r - k, x + r - k, y, x + r, y);
        s.closePath();
    }

    private float medirAnchoTexto(PDFont fuente, String texto, float fontSize) {
        try {
            return fuente.getStringWidth(texto) / 1000f * fontSize;
        } catch (Exception ex) {
            return 0f;
        }
    }

    private PDDocument documentoActual;
    private final Map<String, PDFont> fuentesCargadas = new HashMap<>();

    private PDFont obtenerFuentePDF(String familia, String estilo) throws IOException {
        // Fuente instalada en el ordenador: se incrusta en el PDF (solo las letras que se usan)
        File f = LibroGuardiaUI.FuentesSistema.archivo(familia, estilo, true);
        if (f != null && documentoActual != null) {
            String clave = f.getAbsolutePath();
            if (fuentesCargadas.containsKey(clave)) return fuentesCargadas.get(clave);
            try {
                PDFont pf = org.apache.pdfbox.pdmodel.font.PDType0Font.load(documentoActual, f);
                fuentesCargadas.put(clave, pf);
                return pf;
            } catch (Exception ex) {
                throw new IOException("la tipografía «" + familia + "» no se puede meter en el PDF (" + ex.getMessage() + ")");
            }
        }
        return switch (familia) {
            case "Times-Roman" -> switch (estilo) {
                case "Negrita" -> new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD);
                case "Cursiva" -> new PDType1Font(Standard14Fonts.FontName.TIMES_ITALIC);
                case "Negrita-Cursiva" -> new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD_ITALIC);
                default -> new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
            };
            case "Courier" -> switch (estilo) {
                case "Negrita" -> new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD);
                case "Cursiva" -> new PDType1Font(Standard14Fonts.FontName.COURIER_OBLIQUE);
                case "Negrita-Cursiva" -> new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD_OBLIQUE);
                default -> new PDType1Font(Standard14Fonts.FontName.COURIER);
            };
            default -> switch (estilo) {
                case "Negrita" -> new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
                case "Cursiva" -> new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
                case "Negrita-Cursiva" -> new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE);
                default -> new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            };
        };
    }

    private static class PanelDiseno extends JPanel {
        private BufferedImage imagenFondo;
        private double escala = 1.0;
        private int imgX = 0, imgY = 0, imgW = 0, imgH = 0;

        public PanelDiseno() {
            setPreferredSize(new Dimension(800, 600));
        }

        public void setImagenFondo(BufferedImage img) {
            this.imagenFondo = img;
            recalcularEscala();
        }

        public double getEscala() { return escala; }
        public int getImgX() { return imgX; }
        public int getImgY() { return imgY; }

        public void recalcularEscala() {
            int anchoPanel = getWidth();
            int altoPanel = getHeight();
            if (anchoPanel <= 0) anchoPanel = 800;
            if (altoPanel <= 0) altoPanel = 600;

            if (imagenFondo != null) {
                double escalaX = (double) anchoPanel / imagenFondo.getWidth();
                double escalaY = (double) altoPanel / imagenFondo.getHeight();
                escala = Math.min(escalaX, escalaY);
                imgW = (int) (imagenFondo.getWidth() * escala);
                imgH = (int) (imagenFondo.getHeight() * escala);
                imgX = (anchoPanel - imgW) / 2;
                imgY = (altoPanel - imgH) / 2;
            } else {
                escala = 1.0;
                imgW = anchoPanel;
                imgH = altoPanel;
                imgX = 0;
                imgY = 0;
            }
            revalidate();
            repaint();
            recalcularTodasLasEtiquetasExternas();
        }

        private void recalcularTodasLasEtiquetasExternas() {
            Container p = getParent();
            while (p != null && !(p instanceof GeneradorDiplomaUI)) {
                p = p.getParent();
            }
            if (p instanceof GeneradorDiplomaUI ventana) {
                ventana.recalcularTodasLasEtiquetas();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            g2d.setColor(new Color(240, 240, 240));
            g2d.fillRect(0, 0, getWidth(), getHeight());
            if (imagenFondo != null) {
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.drawImage(imagenFondo, imgX, imgY, imgW, imgH, this);
            }
        }
    }

    /** Recuadro del campo seleccionado: línea discontinua y 8 nodos para cambiar el tamaño arrastrando. */
    private static class BordeConNodos implements javax.swing.border.Border {
        static final int NODO = 7;
        @Override public void paintBorder(Component c, Graphics g0, int x, int y, int w, int h) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setColor(Color.RED);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4f, 2f}, 0f));
            g.drawRect(x + NODO / 2, y + NODO / 2, w - NODO, h - NODO);
            g.setStroke(new BasicStroke(1f));
            int[] xs = {x, x + (w - NODO) / 2, x + w - NODO}, ys = {y, y + (h - NODO) / 2, y + h - NODO};
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
                if (i == 1 && j == 1) continue;
                g.setColor(Color.WHITE);
                g.fillRect(xs[i], ys[j], NODO, NODO);
                g.setColor(Color.RED);
                g.drawRect(xs[i], ys[j], NODO - 1, NODO - 1);
            }
            g.dispose();
        }
        @Override public Insets getBorderInsets(Component c) { return new Insets(1, 1, 1, 1); }
        @Override public boolean isBorderOpaque() { return false; }
    }

    private class ControladorArrastre extends MouseAdapter {
        private final String idCampo;
        private int pressScreenX, pressScreenY;
        private int originalPosX, originalPosY, originalAncho, originalAlto;
        // Qué se arrastra: bordes izquierdo/derecho (-1/1) y superior/inferior (-1/1). 0,0 = mover el campo
        private int bordeX = 0, bordeY = 0;

        public ControladorArrastre(String idCampo) {
            this.idCampo = idCampo;
        }

        /** Nodo bajo el ratón (solo en el campo seleccionado). */
        private int[] nodoEn(MouseEvent e) {
            if (!idCampo.equals(idCampoSeleccionado)) return new int[]{0, 0};
            Component c = e.getComponent();
            int m = BordeConNodos.NODO + 2;
            int bx = e.getX() < m ? -1 : e.getX() > c.getWidth() - m ? 1 : 0;
            int by = e.getY() < m ? -1 : e.getY() > c.getHeight() - m ? 1 : 0;
            return new int[]{bx, by};
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            int[] n = nodoEn(e);
            int cursor = Cursor.MOVE_CURSOR;
            if (n[0] != 0 && n[1] != 0) cursor = (n[0] == n[1]) ? Cursor.NW_RESIZE_CURSOR : Cursor.NE_RESIZE_CURSOR;
            else if (n[0] != 0) cursor = Cursor.E_RESIZE_CURSOR;
            else if (n[1] != 0) cursor = Cursor.S_RESIZE_CURSOR;
            e.getComponent().setCursor(Cursor.getPredefinedCursor(cursor));
        }

        @Override
        public void mousePressed(MouseEvent e) {
            int[] n = nodoEn(e);
            bordeX = n[0];
            bordeY = n[1];
            seleccionarCampo(idCampo);
            pressScreenX = e.getXOnScreen();
            pressScreenY = e.getYOnScreen();
            PropiedadesCampo prop = camposMap.get(idCampo);
            if (prop != null) {
                originalPosX = prop.posXOriginal;
                originalPosY = prop.posYOriginal;
                originalAncho = prop.anchoCaja;
                originalAlto = prop.altoCaja;
            }
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            PropiedadesCampo prop = camposMap.get(idCampo);
            if (prop == null) return;

            int deltaX = e.getXOnScreen() - pressScreenX;
            int deltaY = e.getYOnScreen() - pressScreenY;

            double escala = panelDiseno.getEscala();
            if (escala <= 0) escala = 1.0;
            int dx = (int) Math.round(deltaX / escala), dy = (int) Math.round(deltaY / escala);

            if (bordeX == 0 && bordeY == 0) {
                prop.posXOriginal = Math.max(0, originalPosX + dx);
                prop.posYOriginal = Math.max(0, originalPosY + dy);
            } else {
                // Cambiar el tamaño desde el nodo pulsado (mínimo 10 px)
                if (bordeX == 1) prop.anchoCaja = Math.max(10, originalAncho + dx);
                if (bordeX == -1) {
                    int ancho = Math.max(10, originalAncho - dx);
                    prop.posXOriginal = Math.max(0, originalPosX + originalAncho - ancho);
                    prop.anchoCaja = ancho;
                }
                if (bordeY == 1) prop.altoCaja = Math.max(10, originalAlto + dy);
                if (bordeY == -1) {
                    int alto = Math.max(10, originalAlto - dy);
                    prop.posYOriginal = Math.max(0, originalPosY + originalAlto - alto);
                    prop.altoCaja = alto;
                }
            }

            actualizandoUI = true;
            spinPosX.setValue(prop.posXOriginal);
            spinPosY.setValue(prop.posYOriginal);
            if (spinAnchoCaja != null) spinAnchoCaja.setValue(prop.anchoCaja);
            if (spinAltoCaja != null) spinAltoCaja.setValue(prop.altoCaja);
            actualizandoUI = false;

            recalcularPosicionEtiquetaVisual(prop);
        }
    }
}
