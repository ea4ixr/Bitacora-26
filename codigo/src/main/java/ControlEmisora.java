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

/** CAT y emisoras: mis emisoras, OmniRig, pestaña Emisora, canales CB, dial y SEND */
public class ControlEmisora {
    final LibroGuardiaUI app;   // la ventana principal

    ControlEmisora(LibroGuardiaUI app) {
        this.app = app;
    }

    // ======================= EMISORAS CONFIGURADAS (Configuración > Emisoras) =======================
    private static final String[] MODELOS_EMISORA = {"Kenwood TS-870", "Kenwood TS-570", "Yaesu FT-891", "Yaesu FT-857", "Otra (solo frecuencia y modo)"};

    /** Una emisora de la lista: nombre que le pone el usuario, modelo y número de Rig en OmniRig. */
    static class EmisoraConfig {
        String nombre, modelo;
        int rig;
        boolean activa = true;   // activa = tiene su propia pestaña junto al MAPA
        EmisoraConfig(String nombre, String modelo, int rig) { this.nombre = nombre; this.modelo = modelo; this.rig = rig; }
        boolean es570() { return modelo.contains("570"); }
        boolean es891() { return modelo.contains("891"); }
        boolean conMandos() { return modelo.contains("870") || modelo.contains("570") || modelo.contains("891"); }
    }

    private boolean panelEs570 = false;
    private boolean panelEs891 = false;
    // Yaesu FT-891: anchos de filtro (orden SH, la opción 01 es el primer valor) y desplazamiento IF (orden IS)
    private static final int[] FT891_SSB_ANCHO = {200, 400, 600, 850, 1100, 1350, 1500, 1650, 1800, 1950, 2100,
            2200, 2300, 2400, 2500, 2600, 2700, 2800, 2900, 3000, 3200};
    private static final int[] FT891_CW_ANCHO = {50, 100, 150, 200, 250, 300, 350, 400, 450, 500, 800, 1200,
            1400, 1700, 2000, 2400, 3000};
    private static final int[] FT891_SHIFT = java.util.stream.IntStream.rangeClosed(-12, 12).map(i -> i * 100).toArray();
    /** Órdenes de la FT-891 que llevan un "0" detrás para preguntar (por ejemplo "AG0;"). */
    private static final java.util.Set<String> FT891_CON_P1 = java.util.Set.of(
            "AG", "RG", "SQ", "NL", "NB", "NR", "BC", "RA", "PA", "GT", "SH", "IS", "ML", "PR", "NA", "RL");

    /** Con qué se pregunta (y se reconoce la respuesta) de una orden: "AG" o, en la FT-891, "AG0". */
    private String claveLectura(String orden) {
        if (panelEs891 && orden.length() >= 4 && (orden.startsWith("CO0") || orden.startsWith("BP0"))) return orden.substring(0, 4);
        if (panelEs891 && orden.length() >= 3 && Character.isDigit(orden.charAt(2))
                && FT891_CON_P1.contains(orden.substring(0, 2))) return orden.substring(0, 3);
        return orden.substring(0, 2);
    }
    private JPanel panelEmisoraActual;
    private final java.util.List<JComponent> controlesPanelEmisora = new ArrayList<>();

    private static java.util.List<EmisoraConfig> cargarEmisoras() {
        java.util.List<EmisoraConfig> lista = new ArrayList<>();
        String guardado = LibroGuardiaUI.ConfigApp.get("EMISORAS", "");
        for (String linea : guardado.split("\n")) {
            String[] c = linea.split("\\|");
            if (c.length < 3 || c[0].isBlank()) continue;
            int rig;
            try { rig = Integer.parseInt(c[2].trim()); } catch (NumberFormatException e) { rig = 1; }
            EmisoraConfig e = new EmisoraConfig(c[0], c[1], Math.max(1, Math.min(4, rig)));
            if (c.length >= 4) e.activa = !"0".equals(c[3].trim());
            lista.add(e);
        }
        return lista;
    }

    private static void guardarEmisoras(java.util.List<EmisoraConfig> lista) {
        StringBuilder sb = new StringBuilder();
        for (EmisoraConfig e : lista) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(e.nombre.replace("|", " ").replace("\n", " ")).append("|").append(e.modelo).append("|").append(e.rig).append("|").append(e.activa ? "1" : "0");
        }
        LibroGuardiaUI.ConfigApp.put("EMISORAS", sb.toString());
        emisoraActivaLeida = false;
    }

    /** La emisora que se está usando, o null si no se ha elegido ninguna (entonces todo funciona como antes). */
    static EmisoraConfig emisoraActiva() {
        if (!emisoraActivaLeida) {   // se lee del fichero una sola vez (y otra vez cuando se cambia)
            java.util.List<EmisoraConfig> lista = cargarEmisoras();
            int i = LibroGuardiaUI.ConfigApp.getInt("EMISORA_ACTIVA", -1);
            emisoraActivaCache = (i >= 0 && i < lista.size()) ? lista.get(i) : null;
            emisoraActivaLeida = true;
        }
        return emisoraActivaCache;
    }
    private static volatile EmisoraConfig emisoraActivaCache;
    private static volatile boolean emisoraActivaLeida = false;

    private static void guardarEmisoraActiva(int indice) {
        LibroGuardiaUI.ConfigApp.putInt("EMISORA_ACTIVA", indice);
        emisoraActivaLeida = false;
    }

    /** ¿Se leen los mandos (S-meter, filtros...) de la emisora? */
    private boolean leerMandosDeEmisora() {
        EmisoraConfig activa = emisoraActiva();
        if (activa != null) return activa.conMandos();
        String mod = modeloRig == null ? "" : modeloRig.toUpperCase();
        return mod.contains("870");
    }

    /** Mando que esta emisora no tiene: se ve en gris. */
    private JPanel mandoNoDisponible(String nombre, String motivo) {
        JSlider sl = new JSlider(0, 1, 0);
        sl.setEnabled(false);
        sl.setToolTipText(motivo);
        JPanel fila = filaMando(new JLabel(nombre), sl, new JLabel("—"));
        fila.setToolTipText(motivo);
        return fila;
    }

    /** Vuelve a montar la pestaña EMISORA con los mandos de la emisora elegida. */
    private void reconstruirPanelEmisora() {
        if (contenidoDerecha == null || panelEmisoraActual == null) return;
        boolean seVeia = panelEmisoraActual.isVisible();
        Dimension pref = panelEmisoraActual.getPreferredSize(), min = panelEmisoraActual.getMinimumSize();
        controlesSoloCat.removeAll(controlesPanelEmisora);
        controlesPanelEmisora.clear();
        lectoresEmisora.clear();
        prefijosMandos.clear();
        botonFuncionPorTexto.clear();
        java.util.Set<JComponent> antes = new HashSet<>(controlesSoloCat);
        JPanel nuevo = crearPanelEmisora();
        for (JComponent c : controlesSoloCat) if (!antes.contains(c)) controlesPanelEmisora.add(c);
        nuevo.setPreferredSize(pref);
        nuevo.setMinimumSize(min);
        contenidoDerecha.remove(panelEmisoraActual);
        contenidoDerecha.add(nuevo, "EMISORA");
        panelEmisoraActual = nuevo;
        if (seVeia) cartasDerecha.show(contenidoDerecha, "EMISORA");
        contenidoDerecha.revalidate();
        contenidoDerecha.repaint();
    }

    /** Configuración > Emisoras: lista de emisoras para añadir, editar, borrar y elegir la que se usa. */
    // ---- Qué OmniRig se usa: el que diga Windows (como siempre), el 1 (Afreet, el que usa WSJT-X) o el 2 ----
    private static final String CLSID_OMNIRIG_1 = "0839E8C6-ED30-4950-8087-966F970F0CAE";
    private static final String CLSID_OMNIRIG_2 = "74E87CF5-F8CE-4C38-82AE-1673839DB15F";

    static String idOmniRig() {
        return switch (LibroGuardiaUI.ConfigApp.get("OMNIRIG_VERSION", "AUTO")) {
            case "1" -> "clsid:" + CLSID_OMNIRIG_1;
            case "2" -> "clsid:" + CLSID_OMNIRIG_2;
            default -> "OmniRig.OmniRigX";
        };
    }

    void abrirDialogoEmisoras() {
        JDialog dialogo = new JDialog(app, "Mis emisoras", true);
        dialogo.setLayout(new BorderLayout(10, 10));
        java.util.List<EmisoraConfig> lista = cargarEmisoras();
        int[] activa = {LibroGuardiaUI.ConfigApp.getInt("EMISORA_ACTIVA", -1)};
        DefaultListModel<String> modelo = new DefaultListModel<>();
        JList<String> jl = new JList<>(modelo);
        jl.setFont(new Font("Arial", Font.PLAIN, 14));
        jl.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Runnable pintarLista = () -> {
            int sel = jl.getSelectedIndex();
            modelo.clear();
            for (int i = 0; i < lista.size(); i++) {
                EmisoraConfig e = lista.get(i);
                modelo.addElement((i == activa[0] ? "► " : "    ") + e.nombre + "   ·   " + e.modelo + "   ·   Rig " + e.rig
                        + (e.activa ? "   ·   ACTIVA" : "   ·   no activa") + (i == activa[0] ? "   (EN USO)" : ""));
            }
            if (sel >= 0 && sel < modelo.size()) jl.setSelectedIndex(sel);
        };
        pintarLista.run();

        JLabel ayuda = new JLabel("<html>Cada emisora <b>ACTIVA</b> tiene su propia pestaña junto al MAPA: al pulsarla, el programa pasa a comunicar con ella.<br>"
                + "El <b>Rig</b> es el número que tiene esa emisora en OmniRig (Rig 1 o Rig 2).<br>"
                + "<b>Kenwood TS-570:</b> en OmniRig hay que configurarla como <b>TS-2000</b>.</html>");
        ayuda.setFont(new Font("Arial", Font.PLAIN, 12));
        ayuda.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));

        JButton btnAnadir = new JButton("Añadir"), btnEditar = new JButton("Editar"), btnBorrar = new JButton("Borrar");
        JButton btnUsar = new JButton("Usar esta emisora"), btnCerrar = new JButton("Cerrar");
        JButton btnActivar = new JButton("Activar / Desactivar");
        btnActivar.setToolTipText("Activa: la emisora tiene su propia pestaña junto al MAPA. Desactivada: no sale pestaña.");
        btnActivar.addActionListener(e -> {
            int i = jl.getSelectedIndex();
            if (i < 0) { JOptionPane.showMessageDialog(dialogo, "Elige primero una emisora de la lista."); return; }
            lista.get(i).activa = !lista.get(i).activa;
            guardarEmisoras(lista);
            pintarLista.run();
        });
        btnAnadir.addActionListener(e -> {
            EmisoraConfig nueva = pedirDatosEmisora(dialogo, null);
            if (nueva == null) return;
            lista.add(nueva);
            guardarEmisoras(lista);
            if (lista.size() == 1) {   // la primera que se añade pasa a ser la que se usa
                activa[0] = 0;
                aplicarEmisoraActiva(dialogo, 0, nueva);
            }
            pintarLista.run();
            jl.setSelectedIndex(lista.size() - 1);
        });
        btnEditar.addActionListener(e -> {
            int i = jl.getSelectedIndex();
            if (i < 0) { JOptionPane.showMessageDialog(dialogo, "Elige primero una emisora de la lista."); return; }
            EmisoraConfig editada = pedirDatosEmisora(dialogo, lista.get(i));
            if (editada == null) return;
            lista.set(i, editada);
            guardarEmisoras(lista);
            if (i == activa[0]) aplicarEmisoraActiva(dialogo, i, editada);
            pintarLista.run();
        });
        btnBorrar.addActionListener(e -> {
            int i = jl.getSelectedIndex();
            if (i < 0) { JOptionPane.showMessageDialog(dialogo, "Elige primero una emisora de la lista."); return; }
            if (JOptionPane.showConfirmDialog(dialogo, "¿Borrar la emisora \"" + lista.get(i).nombre + "\" de la lista?",
                    "Borrar emisora", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            lista.remove(i);
            guardarEmisoras(lista);
            if (i == activa[0]) {
                activa[0] = -1;
                aplicarEmisoraActiva(dialogo, -1, null);
            } else if (i < activa[0]) {
                activa[0]--;
                guardarEmisoraActiva(activa[0]);
            }
            pintarLista.run();
        });
        btnUsar.addActionListener(e -> {
            int i = jl.getSelectedIndex();
            if (i < 0) { JOptionPane.showMessageDialog(dialogo, "Elige primero una emisora de la lista."); return; }
            activa[0] = i;
            if (!lista.get(i).activa) { lista.get(i).activa = true; guardarEmisoras(lista); }
            aplicarEmisoraActiva(dialogo, i, lista.get(i));
            pintarLista.run();
        });
        btnCerrar.addActionListener(e -> dialogo.dispose());
        jl.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) btnEditar.doClick(); }
        });

        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        for (JButton b : new JButton[]{btnAnadir, btnEditar, btnBorrar, btnActivar, btnUsar, btnCerrar}) botones.add(b);
        // Qué OmniRig usar (si en el ordenador están instalados el 1 y el 2)
        String[] versiones = {"AUTO", "1", "2"};
        JComboBox<String> cbOmni = new JComboBox<>(new String[]{"El que diga Windows (como hasta ahora)",
                "OmniRig 1 (Afreet) — el que usa WSJT-X", "OmniRig 2"});
        cbOmni.setSelectedIndex(Math.max(0, java.util.Arrays.asList(versiones).indexOf(LibroGuardiaUI.ConfigApp.get("OMNIRIG_VERSION", "AUTO"))));
        cbOmni.setToolTipText("<html>Si tienes instalados OmniRig 1 y OmniRig 2, elige aquí cuál usa Bitácora 26.<br>"
                + "Para usarla a la vez que WSJT-X, todos tienen que usar el mismo: OmniRig 1.</html>");
        cbOmni.addActionListener(e -> {
            String antes = LibroGuardiaUI.ConfigApp.get("OMNIRIG_VERSION", "AUTO"), nueva = versiones[cbOmni.getSelectedIndex()];
            if (nueva.equals(antes)) return;
            LibroGuardiaUI.ConfigApp.put("OMNIRIG_VERSION", nueva);
            if (app.catConectado) JOptionPane.showMessageDialog(dialogo, "Desconecta y vuelve a conectar el CAT para usar el OmniRig elegido.",
                    "OmniRig", JOptionPane.INFORMATION_MESSAGE);
        });
        JPanel filaOmni = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filaOmni.add(new JLabel("OmniRig que usa Bitácora 26:"));
        filaOmni.add(cbOmni);
        // Conectar el CAT solo al abrir el programa (con la emisora en uso), o no
        JCheckBox chkCatAuto = new JCheckBox("Conectar el CAT automáticamente al abrir Bitácora 26",
                !"0".equals(LibroGuardiaUI.ConfigApp.get("CAT_AUTO_ARRANQUE", "1")));
        chkCatAuto.setToolTipText("Marcado: al abrir el programa se conecta solo con la emisora en uso. Sin marcar: pulsa «Conectar CAT» cuando quieras.");
        chkCatAuto.addActionListener(e -> LibroGuardiaUI.ConfigApp.put("CAT_AUTO_ARRANQUE", chkCatAuto.isSelected() ? "1" : "0"));
        filaOmni.add(Box.createHorizontalStrut(18));
        filaOmni.add(chkCatAuto);
        JScrollPane sp = new JScrollPane(jl);
        sp.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12), sp.getBorder()));
        // Sin ninguna emisora: en vez de la lista vacía, una bienvenida con un solo botón (como en la propuesta)
        JPanel bienvenida = new JPanel(new GridBagLayout());
        GridBagConstraints gb = new GridBagConstraints();
        gb.gridx = 0;
        gb.insets = new Insets(4, 24, 4, 24);
        JLabel lbB1 = new JLabel("Todavía no tienes ninguna emisora");
        lbB1.setFont(new Font("Arial", Font.BOLD, 19));
        lbB1.setForeground(Color.WHITE);
        JLabel lbB2 = new JLabel("Conéctala con su cable al ordenador y pulsa el botón: te guiamos paso a paso.");
        lbB2.setFont(new Font("Arial", Font.PLAIN, 13));
        lbB2.setForeground(new Color(190, 200, 215));
        JButton btnAnadirMia = new JButton("Añadir mi emisora");
        btnAnadirMia.setFont(new Font("Arial", Font.BOLD, 14));
        btnAnadirMia.setBackground(app.TEMA_AMBAR);
        btnAnadirMia.setForeground(new Color(27, 20, 4));
        btnAnadirMia.addActionListener(e -> {
            EmisoraConfig nueva = asistenteEmisora(dialogo, lista);
            if (nueva == null) return;
            lista.add(nueva);
            guardarEmisoras(lista);
            if (lista.size() == 1) {   // la primera que se añade pasa a ser la que se usa
                activa[0] = 0;
                aplicarEmisoraActiva(dialogo, 0, nueva);
            }
            pintarLista.run();
            jl.setSelectedIndex(lista.size() - 1);
        });
        JLabel lbB3 = new JLabel("<html><div style='width:520px'>Paso 1: marca y modelo (lista) · Paso 2: puerto (se busca solo) · "
                + "Paso 3: «Probar» (lee la frecuencia). Lo de OmniRig y el Rig 1/2 se hace por debajo.</div></html>");
        lbB3.setFont(new Font("Arial", Font.PLAIN, 12));
        lbB3.setForeground(new Color(150, 165, 185));
        JButton btnCerrarVacia = new JButton("Cerrar");
        btnCerrarVacia.addActionListener(e -> dialogo.dispose());
        gb.gridy = 0; gb.insets = new Insets(26, 24, 4, 24); bienvenida.add(lbB1, gb);
        gb.gridy = 1; gb.insets = new Insets(2, 24, 4, 24); bienvenida.add(lbB2, gb);
        gb.gridy = 2; gb.insets = new Insets(14, 24, 14, 24); bienvenida.add(btnAnadirMia, gb);
        gb.gridy = 3; gb.insets = new Insets(14, 24, 4, 24); gb.anchor = GridBagConstraints.WEST; bienvenida.add(lbB3, gb);
        gb.gridy = 4; gb.insets = new Insets(18, 24, 14, 24); gb.anchor = GridBagConstraints.CENTER; bienvenida.add(btnCerrarVacia, gb);
        CardLayout cartasEm = new CardLayout();
        JPanel centroEm = new JPanel(cartasEm);
        centroEm.add(sp, "LISTA");
        centroEm.add(bienvenida, "VACIA");
        // Con emisoras: la lista con sus botones; sin ninguna: la bienvenida (con su propio «Cerrar»)
        final JPanel[] pie = {null};
        Runnable verVacia = () -> {
            boolean vacia = lista.isEmpty();
            cartasEm.show(centroEm, vacia ? "VACIA" : "LISTA");
            ayuda.setVisible(!vacia);
            if (pie[0] != null) pie[0].setVisible(!vacia);
            if (pie[0] != null) dialogo.setSize(vacia ? new Dimension(720, 390) : new Dimension(860, 420));
        };
        modelo.addListDataListener(new javax.swing.event.ListDataListener() {
            public void intervalAdded(javax.swing.event.ListDataEvent e) { verVacia.run(); }
            public void intervalRemoved(javax.swing.event.ListDataEvent e) { verVacia.run(); }
            public void contentsChanged(javax.swing.event.ListDataEvent e) { verVacia.run(); }
        });
        verVacia.run();
        dialogo.add(ayuda, BorderLayout.NORTH);
        dialogo.add(centroEm, BorderLayout.CENTER);
        JPanel pieEmisoras = new JPanel(new GridLayout(2, 1));
        pieEmisoras.add(filaOmni);
        pieEmisoras.add(botones);
        dialogo.add(pieEmisoras, BorderLayout.SOUTH);
        pie[0] = pieEmisoras;
        verVacia.run();
        dialogo.setLocationRelativeTo(app);
        dialogo.setVisible(true);
        rehacerPestanasEmisora();   // al cerrar: una pestaña por cada emisora activa
    }

    /** Ventanita para escribir el nombre, el modelo y el Rig de una emisora. Devuelve null si se cancela. */
    /** Puertos COM del ordenador (Windows), para el asistente de «Añadir mi emisora». */
    private static java.util.List<String> puertosSerie() {
        java.util.List<String> l = new ArrayList<>();
        try {
            Process pr = new ProcessBuilder("reg", "query", "HKLM\\HARDWARE\\DEVICEMAP\\SERIALCOMM").redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new java.io.InputStreamReader(pr.getInputStream()))) {
                String ln;
                while ((ln = br.readLine()) != null) {
                    if (!ln.contains("REG_SZ")) continue;
                    String[] t = ln.trim().split("\\s+");
                    String com = t[t.length - 1].toUpperCase();
                    if (com.startsWith("COM") && !l.contains(com)) l.add(com);
                }
            }
            pr.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) { }
        l.sort(java.util.Comparator.comparingInt(c -> { try { return Integer.parseInt(c.substring(3)); } catch (Exception e) { return 999; } }));
        return l;
    }

    /** Cómo se llama el modelo en la lista de OmniRig («Rig type»). */
    private static String tipoOmniRig(String modelo) {
        return modelo.contains("570") ? "TS-2000" : modelo.contains("870") ? "TS-870" : modelo.contains("891") ? "FT-891"
                : modelo.contains("857") ? "FT-857" : "el modelo de tu emisora";
    }

    /**
     * «Añadir mi emisora» paso a paso: 1) marca y modelo, 2) puerto (se buscan los COM del ordenador y se abre la
     * ventana de OmniRig ya diciendo qué poner), 3) «Probar» (se lee la frecuencia). El Rig de OmniRig se elige solo.
     * Solo se LEE de la emisora: no se le manda nada.
     */
    private EmisoraConfig asistenteEmisora(Window padre, java.util.List<EmisoraConfig> yaHay) {
        JDialog d = new JDialog(padre, "Añadir mi emisora", Dialog.ModalityType.APPLICATION_MODAL);
        CardLayout cartas = new CardLayout();
        JPanel pasos = new JPanel(cartas);
        JLabel titulo = new JLabel();
        titulo.setFont(new Font("Arial", Font.BOLD, 16));
        titulo.setForeground(app.TEMA_AMBAR);
        titulo.setBorder(BorderFactory.createEmptyBorder(14, 18, 4, 18));
        java.util.function.Function<String, JLabel> texto = t -> {
            JLabel l = new JLabel("<html><div style='width:470px'>" + t + "</div></html>");
            l.setFont(new Font("Arial", Font.PLAIN, 13));
            return l;
        };
        // Cada paso: una columna pegada a la izquierda
        java.util.function.Function<JComponent[], JPanel> columna = comps -> {
            JPanel col = new JPanel();
            col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
            col.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
            for (JComponent x : comps) {
                x.setAlignmentX(Component.LEFT_ALIGNMENT);
                if (x instanceof JPanel fila && fila.getLayout() instanceof FlowLayout) fila.setMaximumSize(fila.getPreferredSize());
                col.add(x);
                col.add(Box.createVerticalStrut(10));
            }
            return col;
        };
        java.util.function.Function<JComponent[], JPanel> filaH = comps -> {
            JPanel f = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            for (JComponent x : comps) f.add(x);
            return f;
        };
        // El Rig de OmniRig: el primero que no use otra emisora (OmniRig 1 tiene Rig 1 y Rig 2)
        int rigLibre = 1;
        java.util.Set<Integer> usados = new HashSet<>();
        for (EmisoraConfig e : yaHay) usados.add(e.rig);
        while (usados.contains(rigLibre) && rigLibre < 4) rigLibre++;
        final int rig = rigLibre;

        // ---- Paso 1: marca y modelo
        JComboBox<String> cbModelo = new JComboBox<>(MODELOS_EMISORA);
        JTextField txtNombre = new JTextField(18);
        app.aplicarFiltroMayusculas(txtNombre);
        Runnable nombrePorDefecto = () -> {
            String m = String.valueOf(cbModelo.getSelectedItem());
            txtNombre.setText(m.startsWith("Otra") ? "MI EMISORA" : m.substring(m.indexOf(' ') + 1).toUpperCase());
        };
        cbModelo.addActionListener(e -> nombrePorDefecto.run());
        nombrePorDefecto.run();
        JLabel lMarca = new JLabel("Marca y modelo:"), lNom = new JLabel("Nombre:");
        lNom.setPreferredSize(lMarca.getPreferredSize());
        JPanel p1 = columna.apply(new JComponent[]{
                texto.apply("Elige la marca y el modelo de tu emisora. El nombre es el que verás en su pestaña (puedes cambiarlo)."),
                filaH.apply(new JComponent[]{lMarca, cbModelo}), filaH.apply(new JComponent[]{lNom, txtNombre})});
        pasos.add(p1, "1");

        // ---- Paso 2: puerto (se busca solo) y OmniRig
        JLabel lblPuertos = new JLabel();
        lblPuertos.setFont(new Font("Arial", Font.BOLD, 13));
        JLabel lblQuePoner = texto.apply("");
        java.util.concurrent.CountDownLatch soltarOmni = new java.util.concurrent.CountDownLatch(1);
        Runnable buscarPuertos = () -> {
            java.util.List<String> l = puertosSerie();
            lblPuertos.setForeground(l.isEmpty() ? new Color(255, 120, 110) : VERDE_ENCENDIDO);
            lblPuertos.setText("<html><div style='width:440px'>" + (l.isEmpty() ? "No encuentro ningún puerto: revisa el cable USB (y su driver) y pulsa «Buscar otra vez»."
                    : "Puerto encontrado: " + String.join(", ", l) + (l.size() > 1 ? "<br><font size='3'>(si no sabes cuál es: desenchufa el cable, pulsa «Buscar otra vez» y mira cuál desaparece)</font>" : ""))
                    + "</div></html>");
            String com = l.size() == 1 ? l.get(0) : "el COM de tu cable";
            lblQuePoner.setText("<html><div style='width:470px'>Pulsa «Abrir OmniRig». En su ventana, en la pestaña <b>RIG " + rig + "</b>, pon:<br>"
                    + "&nbsp;• <b>Rig type:</b> " + tipoOmniRig(String.valueOf(cbModelo.getSelectedItem())) + "<br>"
                    + "&nbsp;• <b>Port:</b> " + com + "<br>"
                    + "&nbsp;• <b>Baud rate:</b> la misma que tenga la emisora en su menú (CAT RATE)<br>"
                    + "y pulsa <b>OK</b>. Luego pulsa «Siguiente».</div></html>");
        };
        JButton bBuscar = new JButton("Buscar otra vez");
        bBuscar.addActionListener(e -> buscarPuertos.run());
        JButton bOmni = new JButton("Abrir OmniRig");
        bOmni.setFont(bOmni.getFont().deriveFont(Font.BOLD));
        JLabel lblOmni = new JLabel(" ");
        lblOmni.setForeground(new Color(255, 120, 110));
        bOmni.addActionListener(e -> {
            lblOmni.setText(" ");
            Thread t = new Thread(() -> {
                ComThread.InitSTA();
                ActiveXComponent omni = null;
                try {
                    omni = new ActiveXComponent(idOmniRig());
                    Dispatch.put(omni, "DialogVisible", new Variant(true));
                    // Mientras el asistente esté abierto se mantiene OmniRig (si no, se cerraría su ventana)
                    soltarOmni.await(30, java.util.concurrent.TimeUnit.MINUTES);
                } catch (Throwable ex) {
                    SwingUtilities.invokeLater(() -> lblOmni.setText("No se ha podido abrir OmniRig: ¿está instalado?"));
                } finally {
                    if (omni != null) try { omni.safeRelease(); } catch (Throwable ignored) { }
                    ComThread.Release();
                }
            }, "AsistenteOmniRig");
            t.setDaemon(true);
            t.start();
        });
        JPanel p2 = columna.apply(new JComponent[]{
                texto.apply("Conecta la emisora con su cable y enciéndela. Busco el puerto (COM) donde está:"),
                lblPuertos, filaH.apply(new JComponent[]{bBuscar}), lblQuePoner, filaH.apply(new JComponent[]{bOmni, lblOmni})});
        pasos.add(p2, "2");

        // ---- Paso 3: Probar (se lee la frecuencia; no se manda nada a la emisora)
        JLabel lblPrueba = new JLabel(" ");
        lblPrueba.setFont(new Font("Dialog", Font.BOLD, 14));   // "Dialog" tiene ✓ y ✗
        JButton bProbar = new JButton("Probar");
        bProbar.setFont(bProbar.getFont().deriveFont(Font.BOLD, 14f));
        bProbar.setBackground(app.TEMA_AMBAR);
        bProbar.setForeground(new Color(27, 20, 4));
        final boolean[] probadaBien = {false};
        bProbar.addActionListener(e -> {
            bProbar.setEnabled(false);
            lblPrueba.setForeground(app.TEMA_TEXTO);
            lblPrueba.setText("Leyendo la emisora…");
            Thread t = new Thread(() -> {
                String res;
                boolean bien = false;
                ComThread.InitSTA();
                ActiveXComponent omni = null;
                try {
                    omni = new ActiveXComponent(idOmniRig());
                    Dispatch r = omni.getProperty("Rig" + rig).toDispatch();
                    int estado = 0, freq = 0;
                    String estadoTxt = "";
                    for (int i = 0; i < 24; i++) {   // hasta 6 segundos
                        estado = Dispatch.get(r, "Status").getInt();
                        freq = Dispatch.get(r, "Freq").getInt();
                        estadoTxt = Dispatch.get(r, "StatusStr").getString();
                        if (estado == 4 && freq > 0) break;   // 4 = en línea
                        Thread.sleep(250);
                    }
                    bien = estado == 4 && freq > 0;
                    res = bien ? "✓ Funciona: leo " + String.format(java.util.Locale.GERMANY, "%,d", freq) + " Hz"
                            : "✗ No contesta (OmniRig dice: " + estadoTxt + "). Revisa el puerto y los baudios (paso 2) y que esté encendida.";
                } catch (Throwable ex) {
                    res = "✗ No se puede hablar con OmniRig (¿está instalado?).";
                } finally {
                    if (omni != null) try { omni.safeRelease(); } catch (Throwable ignored) { }
                    ComThread.Release();
                }
                final String fres = res;
                final boolean fbien = bien;
                SwingUtilities.invokeLater(() -> {
                    probadaBien[0] = fbien;
                    bProbar.setEnabled(true);
                    lblPrueba.setForeground(fbien ? VERDE_ENCENDIDO : new Color(255, 120, 110));
                    lblPrueba.setText("<html><div style='width:470px'>" + fres + "</div></html>");
                });
            }, "AsistenteProbar");
            t.setDaemon(true);
            t.start();
        });
        JPanel p3 = columna.apply(new JComponent[]{
                texto.apply("Pulsa «Probar»: el programa lee la frecuencia de la emisora (no le cambia nada)."),
                filaH.apply(new JComponent[]{bProbar}), lblPrueba});
        pasos.add(p3, "3");

        String[] titulos = {"Paso 1 de 3 · Marca y modelo", "Paso 2 de 3 · Puerto", "Paso 3 de 3 · Probar"};
        int[] paso = {1};
        JButton bAtras = new JButton("Atrás"), bSig = new JButton("Siguiente"), bCancelar = new JButton("Cancelar");
        final EmisoraConfig[] resultado = {null};
        Runnable verPaso = () -> {
            cartas.show(pasos, String.valueOf(paso[0]));
            titulo.setText(titulos[paso[0] - 1]);
            bAtras.setEnabled(paso[0] > 1);
            bSig.setText(paso[0] == 3 ? "Terminar" : "Siguiente");
            if (paso[0] == 2) buscarPuertos.run();
        };
        bAtras.addActionListener(e -> { paso[0]--; verPaso.run(); });
        bSig.addActionListener(e -> {
            if (paso[0] == 1 && txtNombre.getText().trim().isEmpty()) { JOptionPane.showMessageDialog(d, "Escribe un nombre para la emisora."); return; }
            if (paso[0] < 3) { paso[0]++; verPaso.run(); return; }
            if (!probadaBien[0] && JOptionPane.showConfirmDialog(d, "Todavía no se ha leído la frecuencia de la emisora.\n¿Guardarla de todas formas?",
                    "Añadir mi emisora", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            EmisoraConfig nueva = new EmisoraConfig(txtNombre.getText().trim().replace("|", " ").toUpperCase(), String.valueOf(cbModelo.getSelectedItem()), rig);
            nueva.activa = true;
            resultado[0] = nueva;
            d.dispose();
        });
        bCancelar.addActionListener(e -> d.dispose());
        JPanel nav = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        nav.add(bAtras);
        nav.add(bSig);
        nav.add(bCancelar);
        d.setLayout(new BorderLayout());
        d.add(titulo, BorderLayout.NORTH);
        d.add(pasos, BorderLayout.CENTER);
        d.add(nav, BorderLayout.SOUTH);
        verPaso.run();
        d.setSize(580, 420);
        d.setLocationRelativeTo(padre);
        d.setVisible(true);
        soltarOmni.countDown();   // se suelta OmniRig (si se abrió)
        return resultado[0];
    }

    private EmisoraConfig pedirDatosEmisora(Component padre, EmisoraConfig actual) {
        JTextField txtNombre = new JTextField(actual == null ? "" : actual.nombre, 22);
        JComboBox<String> cbModelo = new JComboBox<>(MODELOS_EMISORA);
        JComboBox<String> cbRigE = new JComboBox<>(new String[]{"Rig 1", "Rig 2", "Rig 3", "Rig 4"});
        if (actual != null) {
            cbModelo.setSelectedItem(actual.modelo);
            cbRigE.setSelectedIndex(actual.rig - 1);
        }
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 5, 5, 5);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        String[] etiquetas = {"Nombre:", "Modelo:", "Rig en OmniRig:"};
        JComponent[] campos = {txtNombre, cbModelo, cbRigE};
        for (int i = 0; i < 3; i++) {
            g.gridy = i; g.gridx = 0; g.weightx = 0;
            p.add(new JLabel(etiquetas[i]), g);
            g.gridx = 1; g.weightx = 1;
            p.add(campos[i], g);
        }
        JCheckBox chkActiva = new JCheckBox("Activa (con su propia pestaña junto al MAPA)", actual == null || actual.activa);
        g.gridy = 3; g.gridx = 0; g.gridwidth = 2;
        p.add(chkActiva, g);
        g.gridwidth = 1;
        JLabel nota = new JLabel(" ");
        nota.setFont(new Font("Arial", Font.ITALIC, 12));
        Runnable ponerNota = () -> {
            String m = String.valueOf(cbModelo.getSelectedItem());
            nota.setText(m.contains("570") ? "En OmniRig, esta emisora se configura como TS-2000."
                    : m.contains("870") ? "En OmniRig, esta emisora se configura como TS-870."
                    : m.contains("891") ? "En OmniRig, esta emisora se configura como FT-891."
                    : m.contains("857") ? "<html>En OmniRig, esta emisora se configura como FT-857.<br>Su CAT solo deja controlar frecuencia, modo, VFO, SPLIT y RIT.</html>"
                    : "Con este modelo solo se controlan la frecuencia y el modo.");
        };
        cbModelo.addActionListener(e -> ponerNota.run());
        ponerNota.run();
        g.gridy = 4; g.gridx = 0; g.gridwidth = 2;
        p.add(nota, g);
        while (true) {
            int r = JOptionPane.showConfirmDialog(padre, p, actual == null ? "Añadir emisora" : "Editar emisora",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            String nombre = txtNombre.getText().trim().replace("|", " ").toUpperCase();
            if (nombre.isEmpty()) {
                JOptionPane.showMessageDialog(padre, "Escribe un nombre para la emisora.");
                continue;
            }
            EmisoraConfig nueva = new EmisoraConfig(nombre, String.valueOf(cbModelo.getSelectedItem()), cbRigE.getSelectedIndex() + 1);
            nueva.activa = chkActiva.isSelected();
            return nueva;
        }
    }

    /** Guarda cuál es la emisora en uso, pone su Rig y monta los mandos de la pestaña EMISORA para ella. */
    private void aplicarEmisoraActiva(Component padre, int indice, EmisoraConfig e) {
        boolean cambiaRig = e != null && e.rig != app.rigSeleccionado;
        if (cambiaRig) {
            app.rigSeleccionado = e.rig;
            LibroGuardiaUI.ConfigApp.putInt("RIG_SELECCIONADO", e.rig);
            registroCat("Cambio de emisora: " + e.nombre + " (" + e.modelo + ") -> Rig " + e.rig);
            // Primero se suelta la emisora anterior (deja de recibir preguntas) y DESPUÉS se montan los
            // mandos de la nueva: así a la anterior nunca le llegan órdenes que no son suyas
            if (app.catConectado) reconectarCat();
        }
        guardarEmisoraActiva(indice);
        reconstruirPanelEmisora();
        if (cambiaRig) app.actualizarEstado();
        if (lblModeloEmisora != null) lblModeloEmisora.setText("");
    }

    /** Desconecta el CAT y, cuando ha terminado, lo vuelve a conectar (al Rig que esté elegido ahora). */
    private void reconectarCat() {
        if (!app.catConectado) return;
        ordenesCat.clear();   // nada pendiente para la emisora anterior
        reconectandoCat = true;   // las pestañas siguen a la vista mientras se cambia de emisora
        // Mientras se cambia de emisora se mantiene OmniRig abierto. Si no, al soltar la emisora anterior
        // OmniRig se cierra y al volver a abrirse saluda otra vez a TODAS las emisoras (la Kenwood pitaba)
        // y tarda unos segundos en estar listo.
        java.util.concurrent.CountDownLatch omniListo = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch soltarOmni = new java.util.concurrent.CountDownLatch(1);
        Thread mantener = new Thread(() -> {
            ComThread.InitMTA();
            ActiveXComponent omni = null;
            try {
                omni = new ActiveXComponent(idOmniRig());
                omniListo.countDown();
                soltarOmni.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Throwable ex) {
                registroCat("No se pudo mantener OmniRig abierto durante el cambio: " + ex);
            } finally {
                omniListo.countDown();
                if (omni != null) try { omni.safeRelease(); } catch (Throwable ignored) { }
                ComThread.Release();
            }
        }, "MantenerOmniRig");
        mantener.setDaemon(true);
        mantener.start();
        try { omniListo.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        Thread anterior = app.hiloCat;
        app.alternarConexionCat();   // desconectar
        modeloRig = null;
        Thread espera = new Thread(() -> {
            try { if (anterior != null) anterior.join(3000); Thread.sleep(400); } catch (InterruptedException ignored) { }
            SwingUtilities.invokeLater(() -> { if (!app.catConectado) app.alternarConexionCat(); });   // conectar
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
            soltarOmni.countDown();   // la conexión nueva ya tiene OmniRig abierto
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
            SwingUtilities.invokeLater(() -> {
                reconectandoCat = false;
                if (!app.catConectado) rehacerPestanasEmisora();   // no se pudo conectar: fuera pestañas
            });
        }, "ReconectarCat");
        espera.setDaemon(true);
        espera.start();
    }

    private volatile boolean reconectandoCat = false;

    // ---- Pestañas de emisora (una por cada emisora activa) ----
    private JPanel pestanasEmisoras;
    private ButtonGroup grupoPestanasDerecha;
    private final java.util.List<JToggleButton> togglesEmisora = new ArrayList<>();

    private void estiloPestana(JToggleButton t) {
        t.setFont(new Font("Arial", Font.BOLD, 13));
        t.setFocusable(false);
        t.setContentAreaFilled(false);
        t.setOpaque(true);
        t.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 1, 0, 1, app.TEMA_LINEA),
                BorderFactory.createEmptyBorder(4, 16, 4, 16)));
        Runnable pintar = () -> {
            t.setBackground(t.isSelected() ? app.TEMA_CAMPO : app.TEMA_FONDO_2);
            t.setForeground(t.isSelected() ? app.TEMA_AMBAR : app.TEMA_TEXTO);
        };
        t.addItemListener(e -> pintar.run());
        pintar.run();
    }

    /** Pone una pestaña por cada emisora activa (o una sola "EMISORA" si no hay ninguna configurada). */
    private void rehacerPestanasEmisora() {
        if (pestanasEmisoras == null || grupoPestanasDerecha == null) return;
        boolean seVeiaEmisora = panelEmisoraActual != null && panelEmisoraActual.isVisible();
        for (JToggleButton t : togglesEmisora) grupoPestanasDerecha.remove(t);
        togglesEmisora.clear();
        pestanasEmisoras.removeAll();
        java.util.List<EmisoraConfig> lista = cargarEmisoras();
        int enUso = LibroGuardiaUI.ConfigApp.getInt("EMISORA_ACTIVA", -1);
        java.util.List<Integer> activas = new ArrayList<>();
        for (int i = 0; i < lista.size(); i++) if (lista.get(i).activa) activas.add(i);
        boolean mostrarPestanas = app.catConectado || reconectandoCat;   // sin CAT no hay pestañas de emisora
        // Si la emisora en uso ya no está activa, se pasa a la primera activa
        if (!activas.isEmpty() && !activas.contains(enUso)) {
            enUso = activas.get(0);
            aplicarEmisoraActiva(app, enUso, lista.get(enUso));
        }
        JToggleButton elegido = null;
        if (activas.isEmpty() || !mostrarPestanas) {
            // Sin emisoras configuradas y activas, o sin CAT, no hay pestaña de emisora
        } else {
            for (int i : activas) {
                EmisoraConfig em = lista.get(i);
                JToggleButton t = new JToggleButton(em.nombre);
                estiloPestana(t);
                t.setToolTipText(em.modelo + " · Rig " + em.rig + " de OmniRig");
                final int indice = i;
                t.addActionListener(e -> seleccionarPestanaEmisora(indice));
                togglesEmisora.add(t);
                if (i == enUso) elegido = t;
            }
        }
        for (JToggleButton t : togglesEmisora) {
            grupoPestanasDerecha.add(t);
            t.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 1, 0, 1, app.TEMA_LINEA),
                    BorderFactory.createEmptyBorder(4, 9, 4, 9)));
            Dimension pref = t.getPreferredSize();
            t.setMaximumSize(pref);
            t.setMinimumSize(new Dimension(40, pref.height));
            if (pestanasEmisoras.getComponentCount() > 0) pestanasEmisoras.add(Box.createHorizontalStrut(3));
            pestanasEmisoras.add(t);
        }
        pestanasEmisoras.add(Box.createHorizontalGlue());
        if (seVeiaEmisora && elegido != null && mostrarPestanas) elegido.setSelected(true);
        else if (seVeiaEmisora && tabMapa != null) { tabMapa.setSelected(true); cartasDerecha.show(contenidoDerecha, "MAPA"); }
        pestanasEmisoras.revalidate();
        pestanasEmisoras.repaint();
    }

    /** Al pulsar la pestaña de una emisora: se pasa a comunicar con ella y se ven sus mandos. */
    private void seleccionarPestanaEmisora(int indice) {
        java.util.List<EmisoraConfig> lista = cargarEmisoras();
        if (indice < 0 || indice >= lista.size()) return;
        if (indice != LibroGuardiaUI.ConfigApp.getInt("EMISORA_ACTIVA", -1) || lista.get(indice).rig != app.rigSeleccionado) {
            aplicarEmisoraActiva(app, indice, lista.get(indice));
        }
        cartasDerecha.show(contenidoDerecha, "EMISORA");
    }

    // ======================= CONTROL DE LA EMISORA (OmniRig) =======================
    // Valores de OmniRig (los mismos en la v1 y en la v2)
    private static final int PM_VFOA = 0x800, PM_VFOB = 0x1000, PM_VFOEQUAL = 0x2000, PM_VFOSWAP = 0x4000;
    private static final int PM_VFOAA = 0x80, PM_VFOAB = 0x100, PM_VFOBA = 0x200, PM_VFOBB = 0x400;
    private static final int PM_SPLITON = 0x8000, PM_SPLITOFF = 0x10000;
    private static final int PM_RITON = 0x20000, PM_RITOFF = 0x40000, PM_XITON = 0x80000, PM_XITOFF = 0x100000;
    private static final int PM_CW_U = 0x800000, PM_SSB_U = 0x2000000, PM_SSB_L = 0x4000000, PM_AM = 0x20000000, PM_FM = 0x40000000;

    /** Órdenes pendientes para la emisora. Se ejecutan dentro del hilo del CAT (el único que habla con OmniRig). */
    final java.util.concurrent.ConcurrentLinkedQueue<java.util.function.Consumer<Dispatch>> ordenesCat =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    JPanel panelControlEmisora;
    private JLabel lblCtrlFrecuencia, lblCtrlModo, lblCtrlEstado;

    void procesarOrdenesCat(Dispatch rig) {
        java.util.function.Consumer<Dispatch> orden;
        while ((orden = ordenesCat.poll()) != null) {
            try {
                orden.accept(rig);
            } catch (Exception ex) {
                String msg = ex.getMessage();
                SwingUtilities.invokeLater(() -> {
                    if (lblCtrlEstado != null) { lblCtrlEstado.setForeground(new Color(255, 120, 120)); lblCtrlEstado.setText("No aceptada: " + msg); }
                });
            }
        }
    }

    /** Pone una orden en la cola (solo si el CAT está conectado). */
    void enviarOrden(String descripcion, java.util.function.Consumer<Dispatch> orden) {
        if (!app.catConectado) {
            JOptionPane.showMessageDialog(app,
                    "Primero pulsa \"Conectar CAT\".", "CAT desconectado", JOptionPane.WARNING_MESSAGE);
            return;
        }
        ordenesCat.add(orden);
        if (lblCtrlEstado != null) { lblCtrlEstado.setForeground(new Color(140, 230, 140)); lblCtrlEstado.setText("✓ " + descripcion); }
    }

    private void ordenPropiedad(String descripcion, String propiedad, int valor) {
        enviarOrden(descripcion, rig -> Dispatch.put(rig, propiedad, new Variant(valor)));
    }

    /** Modelo de emisora que dice OmniRig (por ejemplo "TS-870"). Se lee una vez al conectar. */
    private volatile String modeloRig = null;
    private volatile String modoActualCtrl = "";
    private JToggleButton tgSplit, tgRit, tgXit;

    /** Lee VFO, split, RIT y XIT para mostrarlos en el panel de control. */
    void leerEstadoParaControl(Dispatch rig, double freqHz, int modo) {
        // Al conectar, OmniRig puede decir "NONE" unos instantes: se vuelve a preguntar hasta que da el modelo
        if (modeloRig == null || modeloRig.isEmpty() || "NONE".equalsIgnoreCase(modeloRig.trim())) {
            try { modeloRig = Dispatch.get(rig, "RigType").getString(); } catch (Exception ex) { modeloRig = ""; }
        }
        String vfo = "";
        boolean split = false, rit = false, xit = false;
        int off = 0;
        try {
            int v = Dispatch.get(rig, "Vfo").getInt();
            vfo = (v == PM_VFOA || v == PM_VFOAA || v == PM_VFOAB) ? "VFO A" : (v == PM_VFOB || v == PM_VFOBB || v == PM_VFOBA) ? "VFO B" : "";
        } catch (Exception ignored) { }
        try { split = Dispatch.get(rig, "Split").getInt() == PM_SPLITON; } catch (Exception ignored) { }
        try { rit = Dispatch.get(rig, "Rit").getInt() == PM_RITON; } catch (Exception ignored) { }
        try { xit = Dispatch.get(rig, "Xit").getInt() == PM_XITON; } catch (Exception ignored) { }
        try { off = Dispatch.get(rig, "RitOffset").getInt(); } catch (Exception ignored) { }
        String f = String.format(java.util.Locale.GERMANY, "%,.3f", freqHz / 1000.0) + " kHz";
        String m = app.convertirModoOmniRig(modo);
        StringBuilder estado = new StringBuilder(m);
        if (!vfo.isEmpty()) estado.append("   ").append(vfo);
        if (split) estado.append("   SPLIT");
        if (rit || xit) estado.append("   ").append(off >= 0 ? "+" : "").append(off).append(" Hz");
        final boolean fSplit = split, fRit = rit, fXit = xit;
        final int fOff = off;
        final String fVfo = vfo;
        SwingUtilities.invokeLater(() -> {
            if (lblCtrlFrecuencia != null) lblCtrlFrecuencia.setText(f);
            if (lblCtrlModo != null) lblCtrlModo.setText(estado.toString());
            if (!esFt891EnUso()) {   // la FT-891 los lee con sus propias órdenes (ST y CF0)
                if (fVfo.equals("VFO A") || fVfo.equals("VFO B")) marcarVfo(fVfo.equals("VFO B"));
                if (tgSplit != null) tgSplit.setSelected(fSplit);
                if (tgRit != null) tgRit.setSelected(fRit);
                if (tgXit != null) tgXit.setSelected(fXit);
            }
            // La casilla del desplazamiento sigue a la emisora, salvo mientras el usuario la está cambiando
            if (spOffsetRit != null && System.currentTimeMillis() - ultimoCambioOffsetMs > 3000
                    && !Integer.valueOf(fOff).equals(spOffsetRit.getValue())) {
                actualizandoOffsetDesdeEmisora = true;
                spOffsetRit.setValue(fOff);
                actualizandoOffsetDesdeEmisora = false;
            }
            if (!m.equals(modoActualCtrl)) {
                modoActualCtrl = m;
                cargarFiltrosSegunModo();
            }
        });
    }

    /** Botón pequeño para el panel de control. */
    JButton botonCtrl(String texto) {
        JButton b = new JButton(texto);
        estiloCtrl(b);
        return b;
    }

    private void estiloCtrl(AbstractButton b) {
        b.setUI(new BotonEmisoraUI());
        b.setFont(new Font("Arial", Font.BOLD, 13));
        b.setMargin(new Insets(2, 3, 2, 3));
        b.setFocusable(false);
    }

    /** Botón que se queda pulsado (encendido/apagado). Encendido = letras verdes. */
    private JToggleButton toggleCtrl(String texto) {
        JToggleButton t = new JToggleButton(texto);
        estiloCtrl(t);
        t.addItemListener(e -> t.setForeground(t.isSelected() ? VERDE_ENCENDIDO : UIManager.getColor("Button.foreground")));
        return t;
    }

    /** ¿Tenemos preparadas las órdenes especiales (filtros, NB, NR...) para esta emisora? */
    private boolean emisoraConOrdenesDsp() {
        EmisoraConfig activa = emisoraActiva();
        if (activa != null) {
            if (activa.conMandos()) return true;
            JOptionPane.showMessageDialog(app,
                    "Con la emisora \"" + activa.nombre + "\" (" + activa.modelo + ") solo se controlan la frecuencia y el modo.\n"
                            + "Los mandos de la pestaña EMISORA están preparados para la Kenwood TS-870, la TS-570 y la Yaesu FT-891.",
                    "Control de la emisora", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        String mod = modeloRig == null ? "" : modeloRig.toUpperCase();
        if (mod.contains("870")) return true;
        JOptionPane.showMessageDialog(app,
                "DSP, filtros, NB y PWR solo están preparados, de momento, para la Kenwood TS-870.\n"
                        + "OmniRig dice que la emisora conectada es: " + (mod.isEmpty() ? "(desconocida)" : modeloRig),
                "Control de la emisora", JOptionPane.INFORMATION_MESSAGE);
        return false;
    }

    /** Envía una orden directa a la emisora (por ejemplo "NB1;"). No espera respuesta. */
    private void ordenDirecta(String descripcion, String comando) {
        if (!app.catConectado) { enviarOrden(descripcion, rig -> { }); return; }
        if (!emisoraConOrdenesDsp()) return;
        registroCat("Orden a Rig " + app.rigSeleccionado + ": " + comando + "  (" + descripcion + ")");
        enviarOrden(descripcion, rig -> Dispatch.call(rig, "SendCustomCommand",
                new Variant(comando), new Variant(0), new Variant("")));
    }

    // ===================== PESTAÑA "EMISORA" (mandos y filtros, al estilo Ham Radio Deluxe) =====================
    // Órdenes de la Kenwood TS-870. La emisora no devuelve (todavía) sus valores: los mandos marcan lo que se envía.
    private static final int[] SSB_LO = {0, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
    private static final int[] SSB_HI = {1400, 1600, 1800, 2000, 2200, 2400, 2600, 2800, 3000, 3400, 4600, 6000};
    private static final int[] CW_WIDTH = {50, 80, 100, 150, 200, 300, 400, 500, 600, 1000, 2000};
    private static final int[] CW_SHIFT = {400, 450, 500, 550, 600, 650, 700, 750, 800, 850, 900, 950, 1000};

    private JLabel lblModeloEmisora;
    private JSlider slLo, slHi;
    private JLabel lblLoNombre, lblHiNombre, lblLoValor, lblHiValor;
    private int[] valoresLo = new int[0], valoresHi = new int[0];
    private boolean cargandoFiltros = false;

    private static String textoShift(int hz) {
        return hz == 0 ? "0 Hz" : String.format("%+d Hz", hz);
    }

    private static String hzTexto(int hz) {
        return hz >= 1000 ? String.format(java.util.Locale.GERMANY, "%.1f kHz", hz / 1000.0) : hz + " Hz";
    }

    /** Dibujo de los deslizadores como en la propuesta: pista oscura, relleno ámbar y botón claro. */
    private static class DeslizadorEmisoraUI extends javax.swing.plaf.basic.BasicSliderUI {
        DeslizadorEmisoraUI(JSlider s) { super(s); }
        @Override protected Dimension getThumbSize() { return new Dimension(12, 16); }
        @Override public void paintFocus(Graphics g) { }
        @Override public void paintTrack(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int y = trackRect.y + trackRect.height / 2 - 3;
            g2.setColor(new Color(44, 59, 85));
            g2.fillRoundRect(trackRect.x, y, trackRect.width, 6, 6, 6);
            int hasta = thumbRect.x + thumbRect.width / 2;
            boolean activo = slider.isEnabled() && slider.getClientProperty("tocado") != null;
            g2.setColor(activo ? LibroGuardiaUI.TEMA_AMBAR : new Color(110, 100, 70));
            g2.fillRoundRect(trackRect.x, y, Math.max(0, hasta - trackRect.x), 6, 6, 6);
            g2.dispose();
        }
        @Override public void paintThumb(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Rectangle r = thumbRect;
            g2.setPaint(new GradientPaint(0, r.y, new Color(244, 247, 251), 0, r.y + r.height,
                    slider.isEnabled() ? new Color(201, 212, 227) : new Color(140, 150, 165)));
            g2.fillRoundRect(r.x, r.y, r.width - 1, r.height - 1, 4, 4);
            g2.setColor(new Color(125, 142, 168));
            g2.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 4, 4);
            g2.dispose();
        }
    }

    /** Medidor S (barra de colores). Sin lectura de la emisora se queda vacío. */
    private static class MedidorS extends JComponent {
        private int nivel = -1;   // 0..100, -1 = sin lectura
        private final int alto;
        MedidorS() { this(18); }
        MedidorS(int alto) { this.alto = alto; }
        void setNivel(int n) { nivel = n; repaint(); }
        @Override public Dimension getPreferredSize() { return new Dimension(100, alto); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth(), h = getHeight();
            g2.setColor(new Color(5, 9, 15));
            g2.fillRect(0, 0, w, h);
            if (nivel > 0) {
                int lleno = w * nivel / 100;
                g2.setPaint(new LinearGradientPaint(0, 0, w, 0, new float[]{0f, 0.7f, 1f},
                        new Color[]{new Color(60, 156, 60), new Color(216, 192, 48), new Color(216, 64, 48)}));
                g2.fillRect(0, 0, lleno, h);
            }
            g2.setColor(LibroGuardiaUI.TEMA_LINEA);
            g2.drawRect(0, 0, w - 1, h - 1);
            g2.dispose();
        }
    }

    private MedidorS medidorS;
    private JLabel lblMedidorS;
    private volatile boolean emisoraTransmitiendo = false;
    private static final int PM_TX = 0x400000;

    // Botón METER: como la tecla METER de la emisora (SWR → COMP → ALC)
    private static final String[] TEXTOS_METER = {"SWR", "COMP", "ALC"};
    private static final String ESCALA_S = "S  1 .  3 .  5 .  7 .  9 .  +20 .  +40 .  +60";
    private JLabel txtEscalaMedidor;
    private MedidorS medidorRoe;
    private JLabel lblRoe;

    /** Valor aproximado de ROE a partir de la lectura del medidor (0..30). */
    private static String textoRoe(int v) {
        if (v <= 0) return "1.0";
        if (v >= 30) return "∞";
        double[] puntos = {1.0, 1.5, 2.0, 3.0, 5.0, 10.0};   // cada 6 divisiones aprox.
        int tramo = Math.min(4, v / 6);
        double frac = (v - tramo * 6) / 6.0;
        return String.format(java.util.Locale.US, "%.1f", puntos[tramo] + (puntos[tramo + 1] - puntos[tramo]) * frac);
    }
    private JButton btnMeter;
    private int meterActual = 0;   // 0 = aún no se sabe, 1 SWR, 2 COMP, 3 ALC

    private static final String[] TEXTOS_METER_891 = {"SWR", "COMP", "ALC", "PO", "ID"};
    private static final String[] ORDENES_METER_891 = {"MS3;", "MS0;", "MS1;", "MS2;", "MS4;"};

    private void mostrarMeter(int cual) {
        meterActual = cual;
        btnMeter.setText((panelEs891 ? TEXTOS_METER_891 : TEXTOS_METER)[cual - 1]);   // el botón muestra el medidor elegido
    }

    private JButton crearBotonMeter() {
        btnMeter = botonCtrl("METER");
        btnMeter.setToolTipText("Medidor de la emisora al transmitir: cada pulsación cambia SWR → COMP → ALC (como la tecla METER)");
        btnMeter.addActionListener(e -> {
            if (!emisoraConOrdenesDsp()) return;
            int sig = meterActual % (panelEs891 ? 5 : 3) + 1;
            mostrarMeter(sig);
            btnMeter.putClientProperty("usadoMs", System.currentTimeMillis());
            ordenDirecta("medidor " + (panelEs891 ? TEXTOS_METER_891 : TEXTOS_METER)[sig - 1],
                    panelEs891 ? ORDENES_METER_891[sig - 1] : "RM" + sig + ";");
        });
        controlesSoloCat.add(btnMeter);
        return btnMeter;
    }

    // ---------------- LECTURA DE LA EMISORA (lo que tiene puesto) ----------------
    /** Para cada orden ("AG", "NB"...), qué hacer con lo que contesta la emisora. */
    private final Map<String, java.util.function.Consumer<String>> lectoresEmisora = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean actualizandoDesdeEmisora = false;
    private volatile long ultimaRespuestaMs = 0;
    /** 1 mientras se espera la respuesta de la emisora a la última pregunta. */
    private volatile int consultasPendientes = 0;
    /** Órdenes de los mandos (AF, PWR, filtros...): se preguntan más a menudo que las teclas. */
    private final java.util.Set<String> prefijosMandos = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Si el usuario acaba de tocar un mando, durante 3 segundos no se le pisa con lo que dice la emisora. */
    private static boolean recienUsado(JComponent c) {
        Object t = c.getClientProperty("usadoMs");
        return t instanceof Long && System.currentTimeMillis() - (Long) t < 3000;
    }

    private void ponerDesdeEmisora(Runnable r) {
        SwingUtilities.invokeLater(() -> {
            actualizandoDesdeEmisora = true;
            try { r.run(); } finally { actualizandoDesdeEmisora = false; }
        });
    }

    /** Recibe los avisos de OmniRig. JACOB llama a este método cuando llega una respuesta de la emisora. */
    public class ReceptorOmniRig {
        private final int numeroRig;
        ReceptorOmniRig(int numeroRig) { this.numeroRig = numeroRig; }

        public void CustomReply(Variant[] args) {
            try {
                if (args == null || args.length < 3) return;
                if (args[0] != null && args[0].toString().trim().equals(String.valueOf(numeroRig)) == false)
                    registroCatTrafico("(respuesta de otro Rig " + args[0] + ": " + textoDeVariant(args[2]) + ")");
                if (args[0] != null && args[0].getvt() != Variant.VariantNull) {
                    try { if (args[0].changeType(Variant.VariantInt).getInt() != numeroRig) return; } catch (Exception ignored) { }
                }
                String r = textoDeVariant(args[2]);
                registroCatTrafico("Rig " + (args[0] == null ? "?" : args[0].toString()) + " -> " + r);
                consultasPendientes = 0;
                if (r != null) procesarRespuestaEmisora(r);
            } catch (Throwable ex) {
                System.err.println("[EMISORA] Respuesta no entendida: " + ex);
            }
        }

        /** Avisos de OmniRig sobre el estado de las emisoras (solo se apuntan en registro_cat.txt). */
        public void StatusChange(Variant[] a) {
            registroCat("OmniRig avisa: cambia el estado del Rig " + (a != null && a.length > 0 ? a[0] : "?"));
        }
        public void RigTypeChange(Variant[] a) {
            registroCat("OmniRig avisa: cambia el modelo del Rig " + (a != null && a.length > 0 ? a[0] : "?"));
        }
    }

    private static String textoDeVariant(Variant v) {
        if (v == null) return null;
        try {
            if (v.getvt() == Variant.VariantString) return v.getString();
            if ((v.getvt() & Variant.VariantArray) != 0) {
                byte[] b = v.toSafeArray().toByteArray();
                return new String(b, java.nio.charset.StandardCharsets.US_ASCII);
            }
            return v.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    /** Una o varias respuestas seguidas, por ejemplo "SM0012;" o "AG128;". */
    private void procesarRespuestaEmisora(String texto) {
        for (String r : texto.split(";")) {
            r = r.trim();
            if (r.length() < 2) continue;
            ultimaRespuestaMs = System.currentTimeMillis();
            String prefijo = r.substring(0, 2).toUpperCase();
            String carga = r.substring(2);
            if (prefijo.equals("SM")) {   // en recepción: señal S; al transmitir: potencia de salida
                String d = carga.replaceAll("[^0-9]", "");
                if (d.isEmpty()) continue;
                int v = Integer.parseInt(d);   // TS-870: 0000..0030 (15 = S9)
                int pct = Math.min(100, v * 100 / 30);
                String txt = emisoraTransmitiendo ? "TX"
                        : v <= 15 ? "S" + Math.round(v * 9 / 15.0) : "S9+" + Math.round((v - 15) * 60 / 15.0);
                SwingUtilities.invokeLater(() -> {
                    if (medidorS != null) medidorS.setNivel(pct);
                    if (lblMedidorS != null) lblMedidorS.setText(txt);
                });
                continue;
            }
            if (prefijo.equals("RM")) {   // "RMnvvvv": n = medidor elegido en la emisora (1 SWR, 2 COMP, 3 ALC)
                String d = carga.replaceAll("[^0-9]", "");
                if (d.isEmpty()) continue;
                int cual = d.charAt(0) - '0';
                if (cual >= 1 && cual <= 3 && btnMeter != null && !recienUsado(btnMeter)) {
                    SwingUtilities.invokeLater(() -> mostrarMeter(cual));
                }
                // Barra de ROE: la emisora solo da la ROE cuando su METER está en SWR
                if (emisoraTransmitiendo && d.length() >= 5 && cual >= 1 && cual <= 3) {
                    int v = Integer.parseInt(d.substring(1, 5));   // 0..30
                    SwingUtilities.invokeLater(() -> {
                        if (medidorRoe == null) return;
                        if (cual == 1) {
                            medidorRoe.setNivel(Math.min(100, v * 100 / 30));
                            lblRoe.setText(textoRoe(v));
                            medidorRoe.setToolTipText(null);
                        } else {
                            medidorRoe.setNivel(0);
                            lblRoe.setText("(" + TEXTOS_METER[cual - 1] + ")");
                        }
                    });
                }
                continue;
            }
            if (prefijo.equals("MS") && panelEs891) {   // FT-891: medidor elegido (0 COMP, 1 ALC, 3 SWR)
                String d = carga.replaceAll("[^0-9]", "");
                int cual = d.isEmpty() ? 0 : switch (d.charAt(0)) { case '3' -> 1; case '0' -> 2; case '1' -> 3; case '2' -> 4; case '4' -> 5; default -> 0; };
                if (cual > 0 && btnMeter != null && !recienUsado(btnMeter)) SwingUtilities.invokeLater(() -> mostrarMeter(cual));
                continue;
            }
            java.util.function.Consumer<String> lector = null;
            for (int largo = 4; largo >= 3 && lector == null; largo--) {
                if (r.length() < largo) continue;
                lector = lectoresEmisora.get(r.substring(0, largo).toUpperCase());
                if (lector != null) carga = r.substring(largo);
            }
            if (lector == null) lector = lectoresEmisora.get(prefijo);
            if (lector != null) lector.accept(carga);
        }
    }

    /**
     * Hilo aparte que pregunta a la emisora qué tiene puesto (medidor S cada 0,3 s y el resto cada 2 s).
     * Usa su propia conexión con OmniRig, así no toca el hilo del CAT que ya funciona.
     */
    /** Número de la conexión CAT actual: cada conexión nueva lo sube y los hilos de la anterior terminan. */
    volatile int sesionCat = 0;

    void iniciarLecturaEmisora(int numeroRig, int miSesion) {
        Thread hilo = new Thread(() -> {
            ComThread.InitMTA();
            com.jacob.com.DispatchEvents eventos = null;
            try {
                ActiveXComponent omni = new ActiveXComponent(idOmniRig());
                eventos = new com.jacob.com.DispatchEvents(omni, new ReceptorOmniRig(numeroRig), "OmniRig.OmniRigX");
                Dispatch rig = omni.getProperty("Rig" + numeroRig).toDispatch();
                trafico = 0;
                String tipo = "?", estado = "?";
                try { tipo = Dispatch.get(rig, "RigType").getString(); } catch (Exception ignored) { }
                try { estado = Dispatch.get(rig, "StatusStr").getString(); } catch (Exception ignored) { }
                registroCat("Lectura de mandos iniciada (conexión " + miSesion + "): Rig " + numeroRig
                        + " · OmniRig dice modelo=" + tipo + " · estado=" + estado
                        + " · emisora en uso=" + (emisoraActiva() == null ? "ninguna" : emisoraActiva().nombre + " / " + emisoraActiva().modelo)
                        + " · mandos=" + lectoresEmisora.size());
                // Se pregunta de una en una: la siguiente pregunta sale cuando la emisora contesta la anterior.
                // Así nunca se acumula una cola de preguntas en OmniRig (eso era lo que hacía que los mandos
                // tardasen en reflejar lo que se toca en la emisora).
                long ultimoEnvio = 0, ultimoTx = 0, ultimoEstado = 0;
                int turno = 0, idxMandos = 0, idxResto = 0, informes = 0;
                boolean tx = false;
                while (app.catConectado && miSesion == sesionCat) {
                    long ahora = System.currentTimeMillis();
                    if (informes < 8 && ahora - ultimoEstado > 3000) {   // cómo ve OmniRig la emisora (para el registro)
                        ultimoEstado = ahora;
                        informes++;
                        String t = "?", e = "?", f = "?";
                        try { t = Dispatch.get(rig, "RigType").getString(); } catch (Exception ignored) { }
                        try { e = Dispatch.get(rig, "StatusStr").getString(); } catch (Exception ignored) { }
                        try { f = String.valueOf(Dispatch.get(rig, "Freq").changeType(Variant.VariantDouble).getDouble()); } catch (Exception ignored) { }
                        registroCat("Rig " + numeroRig + " según OmniRig: modelo=" + t + " · estado=" + e + " · frecuencia=" + f);
                    }
                    if (!leerMandosDeEmisora()) { Thread.sleep(300); continue; }
                    if (ahora - ultimoTx > 250) {
                        ultimoTx = ahora;
                        try { tx = Dispatch.get(rig, "Tx").getInt() == PM_TX; } catch (Exception ignored) { tx = false; }
                        if (tx != emisoraTransmitiendo && !tx) {
                            // Fin de la transmisión: la ROE se queda en blanco hasta la próxima
                            SwingUtilities.invokeLater(() -> {
                                if (medidorRoe != null) { medidorRoe.setNivel(0); lblRoe.setText("—"); }
                            });
                        }
                        emisoraTransmitiendo = tx;
                    }
                    // Esperar la respuesta anterior (como mucho 1,5 s, por si la emisora no contesta)
                    if (consultasPendientes > 0 && ahora - ultimoEnvio < 1500) { Thread.sleep(15); continue; }
                    if (ahora - ultimoEnvio < 50) { Thread.sleep(15); continue; }   // sin saturar el cable
                    String orden;
                    String extra = consultaExtra;
                    if (extra != null && !tx) {   // pregunta puntual (por ejemplo "FA;" para saber en qué VFO está la FT-891)
                        consultaExtra = null;
                        orden = extra;
                    } else if (tx) {
                        Thread.sleep(100);   // al transmitir no se pregunta nada
                        continue;
                    } else {
                        // Recepción: dos mandos (AF, PWR...), una tecla; y vuelta a empezar
                        int fase = turno++ % 3 + 1;
                        java.util.List<String> mandos = new ArrayList<>(), resto = new ArrayList<>();
                        try {
                            for (String pf : new ArrayList<>(lectoresEmisora.keySet())) (prefijosMandos.contains(pf) ? mandos : resto).add(pf);
                        } catch (RuntimeException cambiando) { Thread.sleep(100); continue; }   // se están montando los mandos
                        java.util.Collections.sort(mandos);
                        java.util.Collections.sort(resto);
                        resto.add(panelEs891 ? "MS" : "RM");   // qué medidor tiene elegido la emisora (botón METER)
                        if (fase != 3 && !mandos.isEmpty()) orden = mandos.get(idxMandos++ % mandos.size()) + ";";
                        else orden = resto.get(idxResto++ % resto.size()) + ";";
                    }
                    if (!app.catConectado || miSesion != sesionCat) break;   // ya se ha cambiado de emisora
                    consultasPendientes = 1;
                    ultimoEnvio = ahora;
                    registroCatTrafico("Rig " + numeroRig + " <- " + orden);
                    consultarEmisora(rig, orden);
                }
            } catch (InterruptedException ignored) {
            } catch (Throwable ex) {
                System.err.println("[EMISORA] No se pudo leer la emisora: " + ex);
                registroCat("ERROR en la lectura de mandos (Rig " + numeroRig + "): " + ex);
                String msg = ex.getMessage();
                SwingUtilities.invokeLater(() -> {
                    if (lblMedidorS != null) lblMedidorS.setToolTipText("No se pudo leer la emisora: " + msg);
                });
            } finally {
                registroCat("Lectura de mandos terminada (conexión " + miSesion + ", Rig " + numeroRig + ")");
                if (eventos != null) try { eventos.safeRelease(); } catch (Throwable ignored) { }
                ComThread.Release();
                SwingUtilities.invokeLater(() -> {
                    if (medidorS != null) medidorS.setNivel(-1);
                    if (lblMedidorS != null) lblMedidorS.setText("—");
                });
            }
        }, "LecturaEmisora");
        hilo.setDaemon(true);
        hilo.start();
    }

    // ---- Registro del CAT (registro_cat.txt, junto al programa): para encontrar fallos con varias emisoras ----
    private static volatile int trafico = 0;
    private static final Object CERROJO_REGISTRO = new Object();
    private static boolean registroIniciado = false;

    static void registroCat(String texto) {
        synchronized (CERROJO_REGISTRO) {
            try {
                File f = new File(System.getProperty("user.dir"), "registro_cat.txt");
                boolean anadir = registroIniciado && f.length() < 3_000_000;
                registroIniciado = true;
                try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(f, anadir), java.nio.charset.StandardCharsets.UTF_8))) {
                    w.println(java.time.LocalTime.now().withNano(0) + "  " + texto);
                }
            } catch (Exception ignored) { }
        }
    }

    /** Las preguntas y respuestas solo se apuntan las 200 primeras de cada conexión (para no llenar el disco). */
    static void registroCatTrafico(String texto) {
        if (trafico++ < 200) registroCat("   " + texto);
    }

    private static void consultarEmisora(Dispatch rig, String orden) {
        try {
            Dispatch.call(rig, "SendCustomCommand", new Variant(orden), new Variant(0), new Variant(";"));
        } catch (Exception ex) {
            System.err.println("[EMISORA] " + orden + " -> " + ex.getMessage());
            registroCat("ERROR al preguntar " + orden + ": " + ex.getMessage());
        }
    }

    /** Fila de un mando: nombre | deslizador | valor. */
    private JPanel filaMando(JLabel nombre, JSlider sl, JLabel valor) {
        JPanel fila = new JPanel(new BorderLayout(6, 0));
        fila.setOpaque(false);
        nombre.setFont(new Font("Arial", Font.BOLD, 12));
        nombre.setForeground(app.TEMA_TEXTO);
        nombre.setPreferredSize(new Dimension(44, 18));
        valor.setFont(new Font("Arial", Font.BOLD, 12));
        valor.setForeground(app.TEMA_AMBAR);
        valor.setHorizontalAlignment(SwingConstants.RIGHT);
        valor.setPreferredSize(new Dimension(58, 18));
        sl.setOpaque(false);
        sl.setFocusable(false);
        sl.setUI(new DeslizadorEmisoraUI(sl));
        sl.addChangeListener(e -> { if (sl.getValueIsAdjusting() || sl.getClientProperty("cargando") == null) sl.putClientProperty("tocado", Boolean.TRUE); });
        fila.add(nombre, BorderLayout.WEST);
        fila.add(sl, BorderLayout.CENTER);
        fila.add(valor, BorderLayout.EAST);
        return fila;
    }

    /** Mando continuo (volumen, potencia...): al moverlo se envía la orden (un cuarto de segundo después). */
    private JPanel mandoEmisora(String nombre, String ayuda, int min, int max,
                                java.util.function.IntFunction<String> texto,
                                java.util.function.IntFunction<String> orden) {
        return mandoEmisora(nombre, ayuda, min, max, texto, orden, 3);
    }

    /** Igual, indicando cuántas cifras trae la respuesta de la emisora (por ejemplo 2 en "RL008": "RL0" + "08"). */
    private JPanel mandoEmisora(String nombre, String ayuda, int min, int max,
                                java.util.function.IntFunction<String> texto,
                                java.util.function.IntFunction<String> orden, int cifras) {
        JSlider sl = new JSlider(min, max, min);
        JLabel valor = new JLabel("—");
        sl.setToolTipText(ayuda);
        javax.swing.Timer envio = new javax.swing.Timer(250, e -> {
            int v = sl.getValue();
            ordenDirecta(nombre + " " + texto.apply(v), orden.apply(v));
        });
        envio.setRepeats(false);
        sl.addChangeListener(e -> {
            valor.setText(texto.apply(sl.getValue()));
            if (actualizandoDesdeEmisora) return;   // lo ha movido la emisora: no se reenvía
            sl.putClientProperty("usadoMs", System.currentTimeMillis());
            if (app.catConectado) envio.restart();
        });
        // Lo que contesta la emisora (por ejemplo "AG128;") mueve el mando
        String prefijo = claveLectura(orden.apply(min));
        prefijosMandos.add(prefijo);
        lectoresEmisora.put(prefijo, carga -> {
            String d = carga.replaceAll("[^0-9]", "");
            if (d.length() < cifras || recienUsado(sl)) return;
            int v = Integer.parseInt(d.substring(0, cifras));
            ponerDesdeEmisora(() -> {
                sl.setValue(Math.max(sl.getMinimum(), Math.min(sl.getMaximum(), v)));
                valor.setText(texto.apply(sl.getValue()));
                sl.putClientProperty("tocado", Boolean.TRUE);
                sl.repaint();
            });
        });
        controlesSoloCat.add(sl);
        return filaMando(new JLabel(nombre), sl, valor);
    }

    /** Botón que va pasando por varios estados (por ejemplo ATT: OFF, 6, 12, 18 dB). */
    private JButton botonCiclo(String[] textos, String[] ordenes, String ayuda) {
        JButton b = botonCtrl(textos[0]);
        b.setFont(new Font("Arial", Font.BOLD, 11));
        b.setMargin(new Insets(5, 1, 5, 1));
        b.setToolTipText(ayuda);
        int[] estado = {0};
        java.util.function.IntConsumer mostrar = i -> {
            estado[0] = i;
            b.setText(textos[i]);
            b.setFont(new Font("Arial", Font.BOLD, textos[i].length() > 7 ? 9 : 11));   // "AGC AUTO" cabe entero
            // ATT, N.R. y AGC: encendido = verde. ANT: siempre normal (las dos antenas son "normales")
            boolean verde = i != 0 && !textos[0].equals("ANT") && !ordenes[i].equals(ordenes[0]);
            b.setForeground(verde ? VERDE_ENCENDIDO : UIManager.getColor("Button.foreground"));
        };
        b.addActionListener(e -> {
            if (!emisoraConOrdenesDsp()) return;
            int sig = (estado[0] + 1) % textos.length;
            if (textos[0].equals("ANT") && sig == 0) sig = 1;   // ANT: solo 1 y 2
            mostrar.accept(sig);
            b.putClientProperty("usadoMs", System.currentTimeMillis());
            ordenDirecta(textos[sig], ordenes[sig]);
        });
        // Respuesta de la emisora: se busca qué estado es
        String prefijo = claveLectura(ordenes[0]);
        lectoresEmisora.put(prefijo, carga -> {
            if (recienUsado(b)) return;
            String respuesta = prefijo + carga + ";";
            int encontrado = -1;
            for (int i = ordenes.length - 1; i >= 0; i--) if (ordenes[i].equals(respuesta)) { encontrado = i; break; }
            if (encontrado < 0 && prefijo.equals("GT0")) {   // FT-891: 4, 5 y 6 son AGC automático
                String d = carga.replaceAll("[^0-9]", "");
                if (!d.isEmpty() && Integer.parseInt(d) >= 4) encontrado = ordenes.length - 1;
            }
            if (encontrado < 0 && prefijo.equals("GT")) {   // AGC: valor libre -> rápido o lento
                String d = carga.replaceAll("[^0-9]", "");
                if (!d.isEmpty()) {
                    int v = Integer.parseInt(d);
                    encontrado = v <= 5 ? 0 : v < 126 ? 1 : v < 210 ? 2 : 3;
                }
            }
            if (encontrado >= 0) {
                int fin = encontrado;
                ponerDesdeEmisora(() -> mostrar.accept(fin));
            }
        });
        controlesSoloCat.add(b);
        return b;
    }

    /** Botón de encender/apagar con una orden para cada cosa. */
    private JToggleButton botonFuncion(String texto, String ordenOn, String ordenOff, String ayuda) {
        JToggleButton t = botonSeleccion(texto);
        t.setFont(new Font("Arial", Font.BOLD, 11));
        t.setMargin(new Insets(5, 1, 5, 1));
        t.setToolTipText(ayuda);
        t.addActionListener(e -> {
            if (!emisoraConOrdenesDsp()) { t.setSelected(!t.isSelected()); return; }
            t.putClientProperty("usadoMs", System.currentTimeMillis());
            ordenDirecta(texto + (t.isSelected() ? " ON" : " OFF"), t.isSelected() ? ordenOn : ordenOff);
        });
        String prefijo = claveLectura(ordenOn);
        lectoresEmisora.put(prefijo, carga -> {
            if (recienUsado(t)) return;
            String respuesta = prefijo + carga + ";";
            if (respuesta.equals(ordenOn)) ponerDesdeEmisora(() -> t.setSelected(true));
            else if (respuesta.equals(ordenOff)) ponerDesdeEmisora(() -> t.setSelected(false));
        });
        botonFuncionPorTexto.put(texto, t);
        controlesSoloCat.add(t);
        return t;
    }

    private final Map<String, JToggleButton> botonFuncionPorTexto = new HashMap<>();

    /** Pone los mandos del filtro según el modo: LO/HI en SSB y AM, WIDTH/SHIFT en CW. */
    private void cargarFiltrosSegunModo() {
        if (slLo == null) return;
        boolean cw = "CW".equals(modoActualCtrl);
        boolean fonia = "USB".equals(modoActualCtrl) || "LSB".equals(modoActualCtrl) || "AM".equals(modoActualCtrl);
        valoresLo = cw ? CW_WIDTH : fonia ? SSB_LO : new int[0];
        valoresHi = cw ? (panelEs570 ? new int[0] : CW_SHIFT) : fonia ? SSB_HI : new int[0];
        lblLoNombre.setText(cw ? "WIDTH" : "LO");
        lblHiNombre.setText(cw ? "SHIFT" : "HI");
        if (panelEs891) {   // FT-891: ancho (WIDTH) y desplazamiento (SHIFT) en SSB y CW
            boolean ssb = "USB".equals(modoActualCtrl) || "LSB".equals(modoActualCtrl);
            valoresLo = cw ? FT891_CW_ANCHO : ssb ? FT891_SSB_ANCHO : new int[0];
            valoresHi = (cw || ssb) ? FT891_SHIFT : new int[0];
            lblLoNombre.setText("WIDTH");
            lblHiNombre.setText("SHIFT");
        }
        cargandoFiltros = true;
        for (JSlider sl : new JSlider[]{slLo, slHi}) sl.putClientProperty("cargando", Boolean.TRUE);
        slLo.setMaximum(Math.max(0, valoresLo.length - 1));
        slHi.setMaximum(Math.max(0, valoresHi.length - 1));
        slLo.setValue(0);
        slHi.setValue(0);
        for (JSlider sl : new JSlider[]{slLo, slHi}) { sl.putClientProperty("cargando", null); sl.putClientProperty("tocado", null); sl.repaint(); }
        cargandoFiltros = false;
        lblLoValor.setText(valoresLo.length == 0 ? "—" : "—");
        lblHiValor.setText(valoresHi.length == 0 ? "—" : "—");
        slLo.setEnabled(app.catConectado && valoresLo.length > 0);
        slHi.setEnabled(app.catConectado && valoresHi.length > 0);
    }

    private JSlider deslizadorFiltro(boolean esLo, JLabel valor) {
        JSlider sl = new JSlider(0, 1, 0);
        javax.swing.Timer envio = new javax.swing.Timer(250, e -> {
            int[] vals = esLo ? valoresLo : valoresHi;
            int idx = sl.getValue();
            if (idx < 0 || idx >= vals.length) return;
            int hz = vals[idx];
            boolean cw = "CW".equals(modoActualCtrl);
            String orden = esLo ? String.format("FW%04d;", hz / 10) : String.format("IS %04d;", hz);
            if (panelEs570) {   // TS-570: SSB/AM con SL/SH (número de opción), CW con FW en hercios
                orden = cw ? (esLo ? String.format("FW%04d;", hz) : null)
                        : String.format(esLo ? "SL%02d;" : "SH%02d;", idx);
                if (orden == null) return;
            }
            if (panelEs891) {
                if (esLo) {
                    // Primero estrecho/ancho (NA) y luego el ancho (SH): como lo hace la propia emisora
                    boolean estrecho = hz <= (cw ? 500 : 1800);
                    ordenDirecta("WIDTH " + hzTexto(hz), "NA0" + (estrecho ? 1 : 0) + ";");
                    ordenDirecta("WIDTH " + hzTexto(hz), String.format("SH01%02d;", idx + 1));
                } else {
                    ordenDirecta("SHIFT " + textoShift(hz), String.format("IS0%d%s%04d;", hz == 0 ? 0 : 1, hz < 0 ? "-" : "+", Math.abs(hz)));
                }
                return;
            }
            String nombre = esLo ? (cw ? "WIDTH " : "LO ") : (cw ? "SHIFT " : "HI ");
            ordenDirecta(nombre + hzTexto(hz), orden);
        });
        envio.setRepeats(false);
        sl.addChangeListener(e -> {
            if (cargandoFiltros) return;
            int[] vals = esLo ? valoresLo : valoresHi;
            int idx = sl.getValue();
            if (idx >= 0 && idx < vals.length) valor.setText(panelEs891 && !esLo ? textoShift(vals[idx]) : hzTexto(vals[idx]));
            if (actualizandoDesdeEmisora) return;
            sl.putClientProperty("usadoMs", System.currentTimeMillis());
            if (app.catConectado) envio.restart();
        });
        if (panelEs891) {
            // FT-891: SH0 contesta "SH0" + encendido + opción (01..); IS0 contesta "IS0" + encendido + signo + hercios
            String clave = esLo ? "SH0" : "IS0";
            prefijosMandos.add(clave);
            lectoresEmisora.put(clave, carga -> {
                int[] vals = esLo ? valoresLo : valoresHi;
                if (recienUsado(sl) || vals.length == 0 || carga.length() < 3) return;
                int fin;
                if (esLo) {
                    String d = carga.replaceAll("[^0-9]", "");
                    if (d.length() < 3) return;
                    int opcion = Integer.parseInt(d.substring(1, 3));
                    if (opcion < 1 || opcion > vals.length) return;   // 00 = el ancho por defecto de la emisora
                    fin = opcion - 1;
                } else {
                    int signo = carga.contains("-") ? -1 : 1;
                    String d = carga.substring(1).replaceAll("[^0-9]", "");
                    if (d.isEmpty()) return;
                    int hz = carga.charAt(0) == '0' ? 0 : signo * Integer.parseInt(d);
                    int mejor = 0;
                    for (int k = 1; k < vals.length; k++) if (Math.abs(vals[k] - hz) < Math.abs(vals[mejor] - hz)) mejor = k;
                    fin = mejor;
                }
                ponerDesdeEmisora(() -> {
                    sl.setValue(fin);
                    sl.putClientProperty("tocado", Boolean.TRUE);
                    valor.setText(esLo ? hzTexto(vals[fin]) : textoShift(vals[fin]));
                    sl.repaint();
                });
            });
            controlesSoloCat.add(sl);
            return sl;
        }
        prefijosMandos.add(panelEs570 ? (esLo ? "SL" : "SH") : (esLo ? "FW" : "IS"));
        if (panelEs570 && esLo) prefijosMandos.add("FW");
        if (panelEs570) {
            // TS-570: SL y SH contestan el número de opción; FW (CW) contesta el ancho en hercios
            lectoresEmisora.put(esLo ? "SL" : "SH", carga -> {
                String d = carga.replaceAll("[^0-9]", "");
                int[] vals = esLo ? valoresLo : valoresHi;
                if (d.isEmpty() || recienUsado(sl) || "CW".equals(modoActualCtrl) || vals.length == 0) return;
                int fin = Math.max(0, Math.min(vals.length - 1, Integer.parseInt(d)));
                ponerDesdeEmisora(() -> {
                    sl.setValue(fin);
                    sl.putClientProperty("tocado", Boolean.TRUE);
                    valor.setText(hzTexto(vals[fin]));
                    sl.repaint();
                });
            });
            if (esLo) lectoresEmisora.put("FW", carga -> {
                String d = carga.replaceAll("[^0-9]", "");
                if (d.isEmpty() || recienUsado(sl) || !"CW".equals(modoActualCtrl) || valoresLo.length == 0) return;
                int hz = Integer.parseInt(d);
                int mejor = 0;
                for (int k = 1; k < valoresLo.length; k++) if (Math.abs(valoresLo[k] - hz) < Math.abs(valoresLo[mejor] - hz)) mejor = k;
                int fin = mejor;
                ponerDesdeEmisora(() -> {
                    sl.setValue(fin);
                    sl.putClientProperty("tocado", Boolean.TRUE);
                    valor.setText(hzTexto(valoresLo[fin]));
                    sl.repaint();
                });
            });
            controlesSoloCat.add(sl);
            return sl;
        }
        // FW (LO/WIDTH, en decenas de Hz) e IS (HI/SHIFT, en Hz): se pone la opción más cercana
        lectoresEmisora.put(esLo ? "FW" : "IS", carga -> {
            String d = carga.replaceAll("[^0-9]", "");
            if (d.isEmpty() || recienUsado(sl)) return;
            int hz = Integer.parseInt(d) * (esLo ? 10 : 1);
            int[] vals = esLo ? valoresLo : valoresHi;
            if (vals.length == 0) return;
            int mejor = 0;
            for (int k = 1; k < vals.length; k++) if (Math.abs(vals[k] - hz) < Math.abs(vals[mejor] - hz)) mejor = k;
            int fin = mejor;
            ponerDesdeEmisora(() -> {
                sl.setValue(fin);
                sl.putClientProperty("tocado", Boolean.TRUE);
                valor.setText(hzTexto(vals[fin]));
                sl.repaint();
            });
        });
        controlesSoloCat.add(sl);
        return sl;
    }

    /** Botón que no se puede usar con esta emisora (se ve en gris y explica por qué). */
    private JButton botonNoDisponible(String texto, String motivo) {
        JButton b = botonCtrl(texto);
        b.setFont(new Font("Arial", Font.BOLD, 11));
        b.setMargin(new Insets(5, 1, 5, 1));
        b.setEnabled(false);
        b.setToolTipText(motivo);
        return b;
    }

    /** Contenido de la pestaña EMISORA (como la propuesta). */
    private JPanel crearPanelEmisora() {
        EmisoraConfig activaPanel = emisoraActiva();
        panelEs570 = activaPanel != null && activaPanel.es570();
        panelEs891 = activaPanel != null && activaPanel.es891();
        if (activaPanel != null && !activaPanel.conMandos()) {
            // Emisoras cuyo CAT no tiene órdenes para volumen, filtros... (FT-857 u otras): solo un aviso
            JPanel aviso = new JPanel(new BorderLayout());
            aviso.setBackground(new Color(15, 24, 38));
            aviso.setBorder(BorderFactory.createLineBorder(app.TEMA_LINEA));
            JLabel txt = new JLabel("<html><div style='text-align:center;width:330px'>" + activaPanel.modelo + "<br><br>"
                    + "El CAT de esta emisora solo permite controlar desde el ordenador<br>"
                    + "la frecuencia, el modo, el VFO, el SPLIT y el RIT.<br><br>"
                    + "Volumen, potencia, filtros y demás se manejan en la propia emisora.</div></html>");
            txt.setFont(new Font("Arial", Font.BOLD, 13));
            txt.setForeground(app.TEMA_TEXTO);
            txt.setHorizontalAlignment(SwingConstants.CENTER);
            aviso.add(txt, BorderLayout.CENTER);
            return aviso;
        }
        final boolean es570 = panelEs570, es891 = panelEs891;
        final String noTiene = "La " + (es570 ? "TS-570" : es891 ? "FT-891" : "emisora") + " no tiene esta función (o no se puede manejar desde el ordenador)";
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(new Color(15, 24, 38));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(app.TEMA_LINEA),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        // Medidor S
        JPanel escala = new JPanel(new BorderLayout());
        escala.setOpaque(false);
        JLabel txtEscala = new JLabel(ESCALA_S);
        txtEscalaMedidor = txtEscala;
        txtEscala.setFont(new Font("Arial", Font.BOLD, 11));
        txtEscala.setForeground(new Color(143, 166, 200));
        lblMedidorS = new JLabel("—");
        lblMedidorS.setFont(new Font("Arial", Font.BOLD, 10));
        lblMedidorS.setForeground(new Color(200, 220, 255));
        lblMedidorS.setToolTipText("Señal recibida (pendiente de leerla de la emisora)");
        escala.add(txtEscala, BorderLayout.WEST);
        escala.add(lblMedidorS, BorderLayout.EAST);
        medidorS = new MedidorS(16);
        medidorS.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
        medidorS.setToolTipText("En recepción: señal (S). Al transmitir: potencia de salida");
        medidorRoe = new MedidorS(10);
        lblRoe = new JLabel("—");
        JPanel filaRoe = new JPanel(new BorderLayout(6, 0));
        filaRoe.setOpaque(false);
        JLabel nRoe = new JLabel("ROE");
        nRoe.setFont(new Font("Arial", Font.BOLD, 10));
        nRoe.setForeground(new Color(143, 166, 200));
        nRoe.setPreferredSize(new Dimension(30, 12));
        lblRoe.setFont(new Font("Arial", Font.BOLD, 10));
        lblRoe.setForeground(new Color(200, 220, 255));
        lblRoe.setHorizontalAlignment(SwingConstants.RIGHT);
        lblRoe.setPreferredSize(new Dimension(48, 12));
        filaRoe.add(nRoe, BorderLayout.WEST);
        filaRoe.add(medidorRoe, BorderLayout.CENTER);
        filaRoe.add(lblRoe, BorderLayout.EAST);
        String ayudaRoe = "ROE al transmitir. La emisora solo la da cuando su METER está en SWR; "
                + "si está en COMP o ALC, aquí sale entre paréntesis y esos valores se ven en la emisora.";
        filaRoe.setToolTipText(ayudaRoe);
        medidorRoe.setToolTipText(ayudaRoe);


        // Mandos (dos columnas, como la propuesta)
        JPanel mandos = new JPanel(new GridLayout(es891 ? 7 : 4, 2, 14, es891 ? 1 : 3));
        mandos.setOpaque(false);
        if (es891) {
            // Yaesu FT-891 (manual CAT de Yaesu)
            mandos.add(mandoEmisora("AF", "Volumen", 0, 255, v -> Math.round(v * 100f / 255) + "", v -> String.format("AG0%03d;", v)));
            mandos.add(mandoEmisora("PWR", "Potencia de salida (la FT-891 baja hasta 5 W)", 5, 100, v -> v + " W", v -> String.format("PC%03d;", v)));
            mandos.add(mandoEmisora("RF", "Ganancia de RF", 0, 30, v -> Math.round(v * 100f / 30) + "", v -> String.format("RG0%03d;", v)));
            mandos.add(mandoEmisora("MIC", "Ganancia del micrófono", 0, 100, v -> v + "", v -> String.format("MG%03d;", v)));
            mandos.add(mandoEmisora("SQL", "Squelch", 0, 100, v -> v + "", v -> String.format("SQ0%03d;", v)));
            mandos.add(mandoEmisora("PROC", "Nivel del procesador de voz", 0, 100, v -> v + "", v -> String.format("PL%03d;", v)));
            mandos.add(mandoEmisora("NB", "Nivel del supresor de ruido", 0, 10, v -> v + "", v -> String.format("NL0%03d;", v)));
            mandos.add(mandoEmisora("N.R.", "Nivel de la reducción de ruido DSP", 1, 15, v -> v + "", v -> String.format("RL0%02d;", v), 2));
            mandos.add(mandoEmisora("MON", "Volumen del monitor", 0, 100, v -> v + "", v -> String.format("ML1%03d;", v)));
            mandos.add(mandoEmisora("VOX", "Sensibilidad del VOX", 0, 100, v -> v + "", v -> String.format("VG%03d;", v)));
            mandos.add(mandoEmisora("V.DLY", "Retardo del VOX", 30, 3000, v -> v + " ms", v -> String.format("VD%04d;", v), 4));
            mandos.add(mandoEmisora("BK.D", "Retardo de la semi break-in (CW)", 30, 3000, v -> v + " ms", v -> String.format("SD%04d;", v), 4));
            mandos.add(mandoEmisora("KEY", "Velocidad del manipulador (CW)", 4, 60, v -> v + " wpm", v -> String.format("KS%03d;", v)));
            mandos.add(mandoEmisora("PITCH", "Tono de CW", 0, 75, v -> (300 + v * 10) + " Hz", v -> String.format("KP%02d;", v), 2));
        } else {
        mandos.add(mandoEmisora("AF", "Volumen", 0, 255, v -> Math.round(v * 100f / 255) + "", v -> String.format("AG%03d;", v)));
        if (es570) mandos.add(mandoEmisora("PWR", "Potencia de salida (la TS-570 baja hasta 5 W)", 5, 100, v -> v + " W", v -> String.format("PC%03d;", v)));
        else mandos.add(mandoEmisora("PWR", "Potencia de salida (la TS-870 baja hasta 10 W)", 10, 100, v -> v + " W", v -> String.format("PC%03d;", v)));
        mandos.add(mandoEmisora("RF", "Ganancia de RF", 0, 255, v -> Math.round(v * 100f / 255) + "", v -> String.format("RG%03d;", v)));
        mandos.add(mandoEmisora("MIC", "Ganancia del micrófono", 0, 100, v -> v + "", v -> String.format("MG%03d;", v)));
        mandos.add(mandoEmisora("SQL", "Squelch", 0, 255, v -> Math.round(v * 100f / 255) + "", v -> String.format("SQ%03d;", v)));
        if (es570) {
            mandos.add(mandoNoDisponible("PROC", noTiene));
            mandos.add(mandoNoDisponible("NB", noTiene));
        } else {
            mandos.add(mandoEmisora("PROC", "Nivel del procesador de voz", 0, 100, v -> v + "", v -> String.format("PL%03d%03d;", v, v)));
            mandos.add(mandoEmisora("NB", "Nivel del supresor de ruido", 1, 10, v -> v + "", v -> String.format("NL%03d;", v)));
        }
        mandos.add(mandoEmisora("KEY", "Velocidad del manipulador (CW)", 10, 60, v -> v + " wpm", v -> String.format("KS%03d;", v)));
        }

        // Filtro DSP
        lblLoNombre = new JLabel("LO");
        lblHiNombre = new JLabel("HI");
        lblLoValor = new JLabel("—");
        lblHiValor = new JLabel("—");
        slLo = deslizadorFiltro(true, lblLoValor);
        slHi = deslizadorFiltro(false, lblHiValor);
        slLo.setToolTipText("SSB/AM: corte de graves.  CW: ancho del filtro.");
        slHi.setToolTipText("SSB/AM: corte de agudos.  CW: desplazamiento (tono).");
        JPanel filtro = new JPanel(new GridLayout(es891 ? 3 : 1, 2, 14, 1));
        filtro.setOpaque(false);
        filtro.add(filaMando(lblLoNombre, slLo, lblLoValor));
        filtro.add(filaMando(lblHiNombre, slHi, lblHiValor));
        if (es891) {
            filtro.add(mandoEmisora("CONT.", "Frecuencia del CONTOUR", 10, 3200, v -> hzTexto(v), v -> String.format("CO01%04d;", v), 4));
            filtro.add(mandoEmisora("NOTCH", "Frecuencia del filtro NOTCH manual", 1, 320, v -> hzTexto(v * 10), v -> String.format("BP01%03d;", v)));
            filtro.add(mandoEmisora("APF", "Filtro de pico de audio (CW): desplazamiento", 0, 50, v -> textoShift((v - 25) * 10), v -> String.format("CO03%04d;", v), 4));
            filtro.add(new JLabel());
        }

        // Funciones (2 filas de 7)
        JPanel funciones = new JPanel(new GridLayout(es891 ? 3 : 2, 7, 3, 4));
        funciones.setOpaque(false);
        if (es891) {
            // Yaesu FT-891. TUNER solo enciende o apaga el acoplador externo: NUNCA arranca la sintonía (AC002), que transmite
            funciones.add(botonCiclo(new String[]{"AGC OFF", "AGC F", "AGC M", "AGC S", "AGC AUTO"},
                    new String[]{"GT00;", "GT01;", "GT02;", "GT03;", "GT04;"}, "AGC: OFF → rápido (F) → medio (M) → lento (S) → automático"));
            funciones.add(botonFuncion("ATT", "RA01;", "RA00;", "Atenuador ON/OFF"));
            funciones.add(botonFuncion("AMP", "PA01;", "PA00;", "Preamplificador: ON = AMP, OFF = IPO (sin preamplificador)"));
            funciones.add(botonFuncion("NB", "NB01;", "NB00;", "Supresor de ruido (Noise Blanker)"));
            funciones.add(botonFuncion("N.R.", "NR01;", "NR00;", "Reducción de ruido DSP (el nivel se ajusta arriba)"));
            funciones.add(botonFuncion("DNF", "BC01;", "BC00;", "DNF: filtro de muesca automático, quita pitidos y portadoras"));
            funciones.add(botonFuncion("NAR", "NA01;", "NA00;", "NARROW: filtro estrecho"));
            funciones.add(botonFuncion("NOTCH", "BP00001;", "BP00000;", "Filtro NOTCH manual (la frecuencia se ajusta arriba)"));
            funciones.add(botonFuncion("CONT.", "CO000001;", "CO000000;", "CONTOUR: realza o atenúa una parte del audio (frecuencia arriba)"));
            funciones.add(botonFuncion("APF", "CO020001;", "CO020000;", "APF: filtro de pico de audio para CW"));
            funciones.add(botonFuncion("PROC", "PR01;", "PR00;", "Procesador de voz"));
            funciones.add(botonFuncion("MIC EQ", "PR11;", "PR10;", "Ecualizador paramétrico del micrófono ON/OFF"));
            funciones.add(botonFuncion("VOX", "VX1;", "VX0;", "VOX"));
            funciones.add(botonFuncion("MON", "ML0001;", "ML0000;", "Monitor: escuchar por el altavoz lo que transmites"));
            funciones.add(botonFuncion("BK-IN", "BI1;", "BI0;", "Break-in de CW"));
            funciones.add(botonFuncion("KEYER", "KR1;", "KR0;", "Manipulador electrónico interno"));
            funciones.add(botonFuncion("LOCK", "LK1;", "LK0;", "Bloqueo del dial"));
            funciones.add(botonFuncion("FAST", "FS1;", "FS0;", "Paso rápido del dial"));
            funciones.add(botonFuncion("TUNER", "AC001;", "AC000;",
                    "Acoplador de antena externo (por ejemplo FC-50) ON/OFF.\n"
                            + "Para sintonizar (que transmite) usa la tecla TUNE de la emisora."));
            funciones.add(crearBotonMeter());
        } else {
        // TS-870: GT va de 000 a 255. De 000 a 005 el AGC está apagado; cuanto más alto, más lento
        funciones.add(botonCiclo(new String[]{"AGC OFF", "AGC F", "AGC M", "AGC S"},
                new String[]{"GT000;", "GT084;", "GT168;", "GT252;"}, "AGC: OFF → rápido (F) → medio (M) → lento (S)"));
        if (es570) {
            funciones.add(botonFuncion("ATT", "RA01;", "RA00;", "Atenuador ON/OFF"));
            funciones.add(botonFuncion("PRE", "PA1;", "PA0;", "Preamplificador ON/OFF"));
        } else {
            funciones.add(botonCiclo(new String[]{"ATT", "ATT 6", "ATT 12", "ATT 18"},
                    new String[]{"RA00;", "RA01;", "RA02;", "RA03;"}, "Atenuador: OFF → 6 → 12 → 18 dB"));
            funciones.add(botonFuncion("AIP", "MX1;", "MX0;",
                    "AIP: baja la ganancia del receptor para que las señales muy fuertes no lo saturen"));
        }
        funciones.add(botonFuncion("NB", "NB1;", "NB0;", "Supresor de ruido (Noise Blanker)"));
        funciones.add(botonCiclo(new String[]{"N.R.", "N.R.1", "N.R.2"},
                new String[]{"NR0;", "NR1;", "NR2;"}, "Reducción de ruido DSP: OFF → 1 → 2"));
        if (es570) funciones.add(botonNoDisponible("A.NOTCH", noTiene));
        else funciones.add(botonFuncion("A.NOTCH", "NT1;", "NT0;", "Auto Notch: quita portadoras fijas"));
        funciones.add(botonFuncion("BEAT C.", "BC1;", "BC0;", "Beat Cancel: quita pitidos"));
        if (es570) funciones.add(botonNoDisponible("TX EQ", noTiene));
        else funciones.add(botonFuncion("TX EQ", "EQ1;", "EQ0;",
                "Ecualizador de transmisión ON/OFF (la curva H, C o B se elige en el menú 31 de la emisora)"));
        funciones.add(botonFuncion("PROC", "PR1;", "PR0;", "Procesador de voz"));
        funciones.add(botonFuncion("VOX", "VX1;", "VX0;", "VOX"));
        if (es570) funciones.add(botonNoDisponible("MON", noTiene));
        else funciones.add(botonFuncion("MON", "MN1;", "MN0;", "Monitor: escuchar por el altavoz lo que transmites"));
        funciones.add(botonCiclo(new String[]{"ANT", "ANT 1", "ANT 2"},
                new String[]{"AN1;", "AN1;", "AN2;"}, "Antena 1 / Antena 2"));
        // TUNER: solo mete o quita el acoplador (IN-LINE / THRU). NO arranca la sintonía, porque eso hace transmitir.
        funciones.add(botonFuncion("TUNER", es570 ? "AC 10;" : "AC110;", es570 ? "AC 00;" : "AC000;",
                "Acoplador de antena interno: ON = en línea (usa la sintonía guardada), OFF = puenteado.\n"
                        + "Para sintonizar (que transmite) usa el botón AT TUNE de la emisora."));
        funciones.add(crearBotonMeter());
        }
        // Con 7 botones por fila, letra un poco más pequeña para que quepan los nombres largos
        for (Component c : funciones.getComponents()) {
            if (c instanceof AbstractButton ab) {
                ab.setFont(new Font("Arial", Font.BOLD, 10));
                ab.setMargin(es891 ? new Insets(3, 0, 3, 0) : new Insets(5, 0, 5, 0));
            }
        }
        if (es891) {
            lectoresEmisora.put("ST", carga -> {
                if (tgSplit == null || recienUsado(tgSplit) || carga.isEmpty()) return;
                boolean on = carga.charAt(0) != '0';
                ponerDesdeEmisora(() -> tgSplit.setSelected(on));
            });
            lectoresEmisora.put("FA", this::respuestaFaFt891);
            lectoresEmisora.put("CF0", carga -> {
                if (tgRit == null || tgXit == null || carga.length() < 2 || recienUsado(tgRit) || recienUsado(tgXit)) return;
                boolean rit = carga.charAt(0) == '1', xit = carga.charAt(1) == '1';
                ponerDesdeEmisora(() -> { tgRit.setSelected(rit); tgXit.setSelected(xit); });
            });
        }
        if (!es891) lectoresEmisora.put("AC", carga -> {   // respuesta "ACxyz": y = 1 si el acoplador está en línea en TX
            JToggleButton t = botonFuncionPorTexto.get("TUNER");
            if (t == null || recienUsado(t) || carga.length() < 2) return;
            boolean enLinea = carga.charAt(1) == '1';
            ponerDesdeEmisora(() -> t.setSelected(enLinea));
        });

        // El S-meter y la ROE ya no se muestran (se quitaron para que los mandos vayan más ágiles)
        // La FT-891 tiene más mandos: sin los rótulos de sección para que quepa todo
        JComponent[] bloques = es891
                ? new JComponent[]{mandos, (JComponent) Box.createVerticalStrut(6), filtro, (JComponent) Box.createVerticalStrut(6), funciones}
                : new JComponent[]{mandos,
                (JComponent) Box.createVerticalStrut(5), rotuloSeccion("FILTRO DSP"), (JComponent) Box.createVerticalStrut(2), filtro,
                (JComponent) Box.createVerticalStrut(5), rotuloSeccion("FUNCIONES"), (JComponent) Box.createVerticalStrut(2), funciones};
        for (JComponent c : bloques) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            // Cada bloque con su altura justa (el espacio que sobre queda abajo)
            c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
            p.add(c);
        }
        p.add(Box.createVerticalGlue());
        cargarFiltrosSegunModo();
        for (JComponent c : controlesSoloCat) c.setEnabled(app.catConectado);
        if (es891) {
            // La FT-891 tiene muchos mandos: si la ventana es baja, la pestaña se puede desplazar hacia abajo
            JPanel envoltura = new JPanel(new BorderLayout());
            envoltura.setOpaque(false);
            JPanel ajustable = new PanelAnchoVentana();
            ajustable.setLayout(new BorderLayout());
            ajustable.setOpaque(false);
            ajustable.add(p, BorderLayout.CENTER);
            JScrollPane sc = new JScrollPane(ajustable, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            sc.setBorder(null);
            sc.setOpaque(false);
            sc.getViewport().setOpaque(false);
            sc.getVerticalScrollBar().setUnitIncrement(16);
            envoltura.add(sc, BorderLayout.CENTER);
            return envoltura;
        }
        return p;
    }

    /**
     * Envuelve un panel para que, si la ventana es más estrecha que lo que necesita, aparezca una barra
     * para desplazarlo de lado en vez de aplastar o esconder sus controles. Si cabe, no se nota nada.
     */
    static class PanelDesplazableAncho extends JScrollPane {
        private final JComponent vista;

        PanelDesplazableAncho(JComponent contenido) {
            super(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            JPanel ajustable = new PanelAnchoMinimo();
            ajustable.setLayout(new BorderLayout());
            ajustable.setOpaque(false);
            ajustable.add(contenido, BorderLayout.CENTER);
            vista = ajustable;
            setViewportView(ajustable);
            setBorder(null);
            setOpaque(false);
            getViewport().setOpaque(false);
            getHorizontalScrollBar().setUnitIncrement(24);
            // Al cambiar el tamaño de la ventana se recalcula si hace falta la barra
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override public void componentResized(java.awt.event.ComponentEvent e) {
                    SwingUtilities.invokeLater(() -> { revalidate(); repaint(); });
                }
            });
        }

        @Override public Dimension getPreferredSize() {
            Dimension v = vista.getPreferredSize();
            int alto = v.height;
            if (getWidth() > 0 && getWidth() < v.width) alto += getHorizontalScrollBar().getPreferredSize().height;
            return new Dimension(v.width, alto);
        }

        @Override public Dimension getMinimumSize() {
            return new Dimension(100, getPreferredSize().height);
        }

        /** Ocupa todo el ancho si cabe; si no, se queda con su ancho y se desplaza. */
        private static class PanelAnchoMinimo extends JPanel implements Scrollable {
            @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
            @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
            @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.width; }
            @Override public boolean getScrollableTracksViewportWidth() {
                return getParent() != null && getParent().getWidth() >= getPreferredSize().width;
            }
            @Override public boolean getScrollableTracksViewportHeight() { return true; }
        }
    }

    /** Panel que ocupa siempre el ancho de la ventana (para desplazarse solo hacia abajo). */
    private static class PanelAnchoVentana extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() > getPreferredSize().height;
        }
    }

    /** Zona derecha: pestañas MAPA y EMISORA. */
    JPanel crearZonaDerecha(JComponent mapa) {
        CardLayout cartas = new CardLayout();
        JPanel contenido = new JPanel(cartas);
        contenido.setOpaque(false);
        contenido.add(mapa, "MAPA");
        java.util.Set<JComponent> antesEmisora = new HashSet<>(controlesSoloCat);
        JPanel emisora = crearPanelEmisora();
        for (JComponent c : controlesSoloCat) if (!antesEmisora.contains(c)) controlesPanelEmisora.add(c);
        panelEmisoraActual = emisora;
        contenido.add(emisora, "EMISORA");
        // La pestaña EMISORA ocupa lo mismo que el mapa (no empuja al panel de la izquierda)
        Dimension tamMapa = mapa.getPreferredSize();
        emisora.setPreferredSize(new Dimension(tamMapa.width, 10));
        emisora.setMinimumSize(new Dimension(tamMapa.width, 10));
        contenido.setPreferredSize(tamMapa);
        JPanel canales = crearPanelCanales();
        canales.setPreferredSize(new Dimension(tamMapa.width, 10));
        contenido.add(canales, "CANALES");
        cartasDerecha = cartas;
        contenidoDerecha = contenido;

        JToggleButton tMapa = new JToggleButton("MAPA");
        JToggleButton tCanales = new JToggleButton("CANALES CB");
        tabCanales = tCanales;
        tabMapa = tMapa;
        for (JToggleButton t : new JToggleButton[]{tMapa, tCanales}) estiloPestana(t);
        ButtonGroup grupo = new ButtonGroup();
        grupo.add(tMapa);
        grupo.add(tCanales);
        grupoPestanasDerecha = grupo;
        pestanasEmisoras = new JPanel();
        pestanasEmisoras.setLayout(new BoxLayout(pestanasEmisoras, BoxLayout.X_AXIS));
        pestanasEmisoras.setOpaque(false);
        tCanales.addActionListener(e -> cartas.show(contenido, "CANALES"));
        tCanales.setVisible(false);   // solo aparece con la banda CB
        tMapa.setSelected(true);
        tMapa.addActionListener(e -> cartas.show(contenido, "MAPA"));
        lblModeloEmisora = new JLabel("");
        lblModeloEmisora.setFont(new Font("Arial", Font.BOLD, 12));
        lblModeloEmisora.setForeground(new Color(143, 166, 200));

        // Las pestañas de las emisoras se estrechan si no caben (no empujan al resto de la ventana)
        JPanel pestanas = new JPanel(new BorderLayout(3, 0));
        pestanas.setOpaque(false);
        pestanas.add(tMapa, BorderLayout.WEST);
        pestanas.add(pestanasEmisoras, BorderLayout.CENTER);
        pestanas.add(tabCanales, BorderLayout.EAST);
        rehacerPestanasEmisora();
        JPanel cabecera = new JPanel(new BorderLayout()) {
            @Override public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                return new Dimension(Math.min(d.width, 200), d.height);
            }
        };
        cabecera.setOpaque(false);
        cabecera.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        cabecera.add(pestanas, BorderLayout.CENTER);
        cabecera.add(lblModeloEmisora, BorderLayout.EAST);

        JPanel zona = new JPanel(new BorderLayout());
        zona.setOpaque(false);
        zona.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        zona.add(cabecera, BorderLayout.NORTH);
        zona.add(contenido, BorderLayout.CENTER);
        return zona;
    }

    /** Convierte lo escrito (7090 / 7090,5 / 7.090,000 / 7.09) a hercios. Devuelve -1 si no vale. */
    private static long textoAHercios(String texto) {
        String t = texto.trim().toLowerCase().replace("khz", "").replace("mhz", "").trim();
        if (t.contains(".") && t.contains(",")) t = t.replace(".", "").replace(',', '.');
        else t = t.replace(',', '.');
        try {
            double v = Double.parseDouble(t);
            long hz = v < 1000 ? Math.round(v * 1_000_000) : Math.round(v * 1000);   // MHz o kHz
            return (hz < 100_000 || hz > 470_000_000) ? -1 : hz;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    JLabel lblActividadCab, lblBandaModoLcd;
    private final Map<String, AbstractButton> botonesBanda = new LinkedHashMap<>();
    private final Map<String, AbstractButton> botonesModo = new LinkedHashMap<>();
    private final java.util.List<JComponent> controlesSoloCat = new ArrayList<>();
    // "Encendido": verde, como el botón "Conectar CAT" (un poco más vivo para que se lea bien sobre el fondo oscuro)
    static final Color VERDE_ENCENDIDO = new Color(20, 200, 20);

    /**
     * Aspecto de los botones de la emisora (bandas, modos, VFO y funciones): color del fondo de la aplicación
     * con relieve, texto blanco y, lo que está encendido o seleccionado, en naranja.
     */
    static class BotonEmisoraUI extends javax.swing.plaf.basic.BasicButtonUI {
        static final Color TEXTO = Color.WHITE, APAGADO = new Color(105, 120, 145);
        @Override public void installDefaults(AbstractButton b) {
            super.installDefaults(b);
            b.setOpaque(false);
            b.setBorderPainted(false);
            b.setContentAreaFilled(false);
            b.setRolloverEnabled(true);
        }
        @Override public void paint(Graphics g0, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            ButtonModel m = b.getModel();
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = c.getWidth(), h = c.getHeight();
            boolean hundido = (m.isArmed() && m.isPressed()) || m.isSelected();
            Color arriba = hundido ? new Color(20, 28, 42) : m.isRollover() && b.isEnabled() ? new Color(62, 80, 108) : new Color(50, 66, 92);
            Color abajo = hundido ? new Color(34, 46, 66) : new Color(26, 36, 52);
            g.setPaint(new GradientPaint(0, 0, arriba, 0, h, abajo));
            g.fillRoundRect(0, 0, w - 1, h - 1, 7, 7);
            // relieve: brillo arriba y sombra abajo (al revés si está pulsado)
            g.setColor(hundido ? new Color(0, 0, 0, 120) : new Color(255, 255, 255, 45));
            g.drawLine(3, 1, w - 4, 1);
            g.setColor(hundido ? new Color(255, 255, 255, 30) : new Color(0, 0, 0, 110));
            g.drawLine(3, h - 2, w - 4, h - 2);
            g.setColor(new Color(10, 15, 24));
            g.drawRoundRect(0, 0, w - 1, h - 1, 7, 7);
            g.dispose();
            super.paint(g0, c);
        }
        @Override protected void paintText(Graphics g, AbstractButton b, Rectangle r, String texto) {
            boolean encendido = b.getModel().isSelected() || VERDE_ENCENDIDO.equals(b.getForeground());
            g.setColor(!b.isEnabled() ? APAGADO : encendido ? VERDE_ENCENDIDO : TEXTO);
            g.setFont(b.getFont());
            FontMetrics fm = g.getFontMetrics();
            javax.swing.plaf.basic.BasicGraphicsUtils.drawStringUnderlineCharAt(b, (Graphics2D) g, texto, -1, r.x, r.y + fm.getAscent());
        }
        @Override protected void paintButtonPressed(Graphics g, AbstractButton b) { }
        @Override protected void paintFocus(Graphics g, AbstractButton b, Rectangle vr, Rectangle tr, Rectangle ir) { }
    }

    static JLabel rotuloLcd(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(new Font("Arial", Font.BOLD, 11));
        l.setForeground(new Color(143, 166, 200));
        return l;
    }

    static JLabel rotuloSeccion(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(new Font("Arial", Font.BOLD, 10));
        l.setForeground(new Color(111, 134, 171));
        return l;
    }

    /**
     * Campo de frecuencia tipo display de emisora: siempre se ven las 9 casillas ___.___.___
     * (MHz . kHz . Hz). Se pincha en la casilla que se quiera y se escribe la cifra, que sustituye a la
     * que hubiera; el cursor salta solo a la casilla siguiente.
     * Para el resto del programa se comporta como antes: getText() devuelve "7.100.000" (o "" si está vacío).
     */
    static class CampoFrecuencia extends JTextField {
        private static final String VACIO = "   .   .   ";
        private boolean escribiendoInterno = false;

        CampoFrecuencia() {
            super("", 12);
            ((javax.swing.text.AbstractDocument) getDocument()).setDocumentFilter(new javax.swing.text.DocumentFilter() {
                @Override public void insertString(FilterBypass fb, int o, String t, javax.swing.text.AttributeSet a)
                        throws javax.swing.text.BadLocationException { if (escribiendoInterno) fb.insertString(o, t, a); }
                @Override public void replace(FilterBypass fb, int o, int l, String t, javax.swing.text.AttributeSet a)
                        throws javax.swing.text.BadLocationException { if (escribiendoInterno) fb.replace(o, l, t, a); }
                @Override public void remove(FilterBypass fb, int o, int l)
                        throws javax.swing.text.BadLocationException { if (escribiendoInterno) fb.remove(o, l); }
            });
            setText("");
            // Cada cifra va a la casilla donde está el cursor
            addKeyListener(new KeyAdapter() {
                @Override public void keyTyped(KeyEvent e) {
                    char c = e.getKeyChar();
                    if (Character.isDigit(c)) escribirCifra(c);
                    if (c != '\n') e.consume();
                }
            });
            // Retroceso: borra la casilla anterior. Suprimir: borra la casilla del cursor.
            getActionMap().put(javax.swing.text.DefaultEditorKit.deletePrevCharAction, new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) { borrarCasilla(true); }
            });
            getActionMap().put(javax.swing.text.DefaultEditorKit.deleteNextCharAction, new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) { borrarCasilla(false); }
            });
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { repaint(); }
                @Override public void focusLost(FocusEvent e) { repaint(); }
            });
            // Al mover el cursor se repinta todo el campo (si no, se quedan restos de la casilla anterior)
            addCaretListener(e -> repaint());
            getCaret().setBlinkRate(0);   // el cursor es la rayita de la casilla; no hace falta que parpadee
        }

        private static boolean esPunto(int pos) { return pos == 3 || pos == 7; }

        private String casillas() { return super.getText(); }

        private void ponerCasillas(String t) {
            // Se conserva la casilla donde está el cursor (si no, saltaría al final cada vez que
            // la emisora actualiza la frecuencia)
            int cursor = getCaretPosition();
            escribiendoInterno = true;
            try { super.setText(t); } finally { escribiendoInterno = false; }
            try { setCaretPosition(Math.min(cursor, getDocument().getLength())); } catch (Exception ignored) { }
        }

        private void escribirCifra(char cifra) {
            String t = casillas();
            int pos = getSelectionStart();
            if (esPunto(pos)) pos++;
            if (pos >= t.length()) return;
            ponerCasillas(t.substring(0, pos) + cifra + t.substring(pos + 1));
            int sig = pos + 1;
            if (esPunto(sig)) sig++;
            setCaretPosition(Math.min(sig, t.length()));
        }

        private void borrarCasilla(boolean anterior) {
            String t = casillas();
            int pos = getCaretPosition() - (anterior ? 1 : 0);
            if (anterior && esPunto(pos)) pos--;
            if (!anterior && esPunto(pos)) pos++;
            if (pos < 0 || pos >= t.length()) return;
            ponerCasillas(t.substring(0, pos) + ' ' + t.substring(pos + 1));
            setCaretPosition(anterior ? pos : Math.min(pos + 1, t.length()));
        }

        /** Devuelve la frecuencia como siempre ("7.100.000"), o "" si no hay ninguna cifra. */
        @Override
        public String getText() {
            String t = casillas();
            if (t == null || t.length() != VACIO.length() || t.replaceAll("[ .]", "").isEmpty()) return "";
            String mhz = t.substring(0, 3).replace(" ", "");
            String khz = t.substring(4, 7).replace(' ', '0');
            String hz = t.substring(8, 11).replace(' ', '0');
            long valor = (mhz.isEmpty() ? 0 : Long.parseLong(mhz)) * 1_000_000L + Long.parseLong(khz) * 1000L + Long.parseLong(hz);
            return valor == 0 ? "" : String.format(java.util.Locale.GERMANY, "%,d", valor);
        }

        /** Acepta la frecuencia en hercios con puntos ("7.116.280") y la coloca en sus casillas. */
        @Override
        public void setText(String texto) {
            String d = texto == null ? "" : texto.replaceAll("[^0-9]", "");
            if (d.isEmpty() || d.length() > 9 || Long.parseLong(d) == 0) {
                ponerCasillas(VACIO);
                return;
            }
            String nueve = String.format("%9s", Long.toString(Long.parseLong(d)));
            ponerCasillas(nueve.substring(0, 3) + "." + nueve.substring(3, 6) + "." + nueve.substring(6, 9));
        }

        /** Pegar: se coloca la frecuencia pegada en las casillas. */
        @Override
        public void paste() {
            try {
                Object o = Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                long hz = herciosDeTexto(o == null ? "" : o.toString());
                if (hz > 0) setText(String.format(java.util.Locale.GERMANY, "%,d", hz));
            } catch (Exception ignored) { }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int cw = fm.charWidth('0');
            int x0 = getInsets().left;
            int baseY = getInsets().top + (getHeight() - getInsets().top - getInsets().bottom - fm.getHeight()) / 2 + fm.getAscent();
            // Casilla donde se va a escribir, resaltada
            if (isFocusOwner() && isEditable()) {
                int pos = getCaretPosition();
                if (esPunto(pos)) pos++;
                if (pos < VACIO.length()) {
                    g2.setColor(new Color(LibroGuardiaUI.TEMA_AMBAR.getRed(), LibroGuardiaUI.TEMA_AMBAR.getGreen(), LibroGuardiaUI.TEMA_AMBAR.getBlue(), 45));
                    g2.fillRect(x0 + pos * cw, baseY - fm.getAscent() + 4, cw, fm.getAscent() + 4);
                }
            }
            g2.dispose();
            // Cifras de 7 segmentos, como el display de la emisora (los segmentos apagados se ven tenues)
            Graphics2D g3 = (Graphics2D) g.create();
            int altoCifra = fm.getAscent() - 2;
            pintarSieteSegmentos(g3, casillas(), x0, baseY + 2 - altoCifra, cw, altoCifra, getForeground());
            // Cursor fino bajo la casilla donde se va a escribir
            if (isFocusOwner() && isEditable()) {
                int pos = getCaretPosition();
                if (esPunto(pos)) pos++;
                if (pos < VACIO.length()) {
                    g3.setColor(LibroGuardiaUI.TEMA_AMBAR);
                    g3.fillRect(x0 + pos * cw + 2, baseY + 4, cw - 4, 2);
                }
            }
            g3.dispose();
        }
    }

    // Segmentos encendidos de cada cifra: a (arriba), b, c (derecha), d (abajo), e, f (izquierda), g (centro)
    private static final String[] SEGMENTOS = {"abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgedc", "abc", "abcdefg", "abcdfg"};

    /**
     * Dibuja el texto (cifras, espacios, puntos y guiones) con cifras de 7 segmentos un poco inclinadas,
     * como el display de las emisoras. Cada carácter ocupa "ancho" píxeles; los segmentos apagados se ven tenues.
     */
    static void pintarSieteSegmentos(Graphics2D g0, String texto, int x0, int y0, int ancho, int alto, Color color) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color apagado = new Color(color.getRed(), color.getGreen(), color.getBlue(), 28);
        double w = ancho * 0.80, t = Math.max(2.5, ancho * 0.15), h = alto, mitad = h / 2.0;
        double incl = 0.08;   // inclinación (como la letra de las Kenwood)
        for (int i = 0; i < texto.length(); i++) {
            char ch = texto.charAt(i);
            double x = x0 + i * ancho + (ancho - w) / 2.0;
            if (ch == '.') {
                double tam = t * 1.1;
                g.setColor(color);
                g.fill(new java.awt.geom.Rectangle2D.Double(x + w / 2 - tam / 2 - incl * 0, y0 + h - tam, tam, tam));
                continue;
            }
            String on = ch >= '0' && ch <= '9' ? SEGMENTOS[ch - '0'] : ch == '-' ? "g" : "";
            for (char sgm : "abcdefg".toCharArray()) {
                java.awt.geom.Path2D.Double p = segmento(sgm, x, y0, w, h, mitad, t);
                // inclinación: lo de arriba un poco más a la derecha
                java.awt.geom.AffineTransform at = new java.awt.geom.AffineTransform(1, 0, -incl, 1, incl * (y0 + h), 0);
                g.setColor(on.indexOf(sgm) >= 0 ? color : apagado);
                g.fill(at.createTransformedShape(p));
            }
        }
        g.dispose();
    }

    /** Forma de un segmento (hexágono alargado) dentro de la casilla de una cifra. */
    private static java.awt.geom.Path2D.Double segmento(char sgm, double x, double y, double w, double h, double mitad, double t) {
        double hg = 0.6;   // pequeño hueco entre segmentos
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        switch (sgm) {
            case 'a', 'd', 'g' -> {
                double yc = sgm == 'a' ? y + t / 2 : sgm == 'd' ? y + h - t / 2 : y + mitad;
                double x1 = x + hg, x2 = x + w - hg;
                p.moveTo(x1, yc);
                p.lineTo(x1 + t / 2, yc - t / 2);
                p.lineTo(x2 - t / 2, yc - t / 2);
                p.lineTo(x2, yc);
                p.lineTo(x2 - t / 2, yc + t / 2);
                p.lineTo(x1 + t / 2, yc + t / 2);
            }
            default -> {
                boolean derecha = sgm == 'b' || sgm == 'c';
                boolean arriba = sgm == 'b' || sgm == 'f';
                double xc = derecha ? x + w - t / 2 : x + t / 2;
                double y1 = (arriba ? y + t / 2 : y + mitad) + hg, y2 = (arriba ? y + mitad : y + h - t / 2) - hg;
                p.moveTo(xc, y1);
                p.lineTo(xc + t / 2, y1 + t / 2);
                p.lineTo(xc + t / 2, y2 - t / 2);
                p.lineTo(xc, y2);
                p.lineTo(xc - t / 2, y2 - t / 2);
                p.lineTo(xc - t / 2, y1 + t / 2);
            }
        }
        p.closePath();
        return p;
    }

    /** Modo habitual según la frecuencia: CW en los tramos de telegrafía y, si no, el de la banda. */
    private static String modoPorFrecuencia(long hz, String banda) {
        double k = hz / 1000.0;
        boolean cw = (k >= 1810 && k < 1838) || (k >= 3500 && k < 3570) || (k >= 7000 && k < 7040)
                || (k >= 10100 && k <= 10150) || (k >= 14000 && k < 14070) || (k >= 18068 && k < 18095)
                || (k >= 21000 && k < 21070) || (k >= 24890 && k < 24915) || (k >= 28000 && k < 28070)
                || (k >= 50000 && k < 50100);
        if (cw) return "CW";
        LibroGuardiaUI.InfoBanda info = LibroGuardiaUI.CONFIG_BANDAS.get(banda);
        return info == null ? "" : info.modo;
    }

    /**
     * Frecuencia escrita a mano (sin CAT): se completa (7 = 7 MHz, 7100 = 7.100 kHz) y se ponen solas
     * la banda y el modo. Devuelve true si ha cambiado algo.
     */
    /** true mientras la banda se cambia porque la ha detectado la frecuencia (no hay que poner la típica). */
    boolean bandaPuestaPorFrecuencia = false;
    /** RX copia a TX mientras se escribe, salvo que el usuario haya puesto una RX distinta. */
    private boolean rxVinculada = true;

    /** Hercios del campo de frecuencia (exactos), o -1 si no es una frecuencia válida. */
    private static long herciosDeCampo(JTextField campo) {
        String d = campo.getText().replaceAll("[^0-9]", "");
        if (d.isEmpty() || d.length() > 9) return -1;
        long hz = Long.parseLong(d);
        return (hz < 100_000 || hz > 470_000_000) ? -1 : hz;
    }

    /** Pone banda y modo según la frecuencia de TX (sin tocar las frecuencias escritas). */
    private boolean ajustarBandaYModo(long hz) { return ajustarBandaYModo(hz, true); }

    /** Igual, pero con cambiarModo = false solo cambia la banda (el dial y UP/DOWN no tocan el modo). */
    private boolean ajustarBandaYModo(long hz, boolean cambiarModo) {
        boolean cambio = false;
        String banda = app.bandaPorFrecuencia(hz);
        String bandaActual = app.cbBanda.getSelectedItem() == null ? "" : app.cbBanda.getSelectedItem().toString();
        String modo = modoPorFrecuencia(hz, banda);
        String modoActual = app.cbModo.getSelectedItem() == null ? "" : app.cbModo.getSelectedItem().toString();
        if (!banda.isEmpty() && !banda.equals(bandaActual) && comboContiene(app.cbBanda, banda)) {
            bandaPuestaPorFrecuencia = true;
            try { app.cbBanda.setSelectedItem(banda); } finally { bandaPuestaPorFrecuencia = false; }
            cambio = true;
        }
        if (cambiarModo && !modo.isEmpty() && !modo.equals(modoActual) && comboContiene(app.cbModo, modo)) {
            // En fonía se respeta lo que ya tenía el usuario (LSB/USB/SSB) dentro de la misma banda
            boolean esFonia = "LSB".equals(modo) || "USB".equals(modo);
            boolean actualFonia = "LSB".equals(modoActual) || "USB".equals(modoActual) || "SSB".equals(modoActual);
            if (!(esFonia && actualFonia && banda.equals(bandaActual))) {
                app.cbModo.setSelectedItem(modo);
                cambio = true;
            }
        }
        refrescarFrontal();
        return cambio;
    }

    /** Mientras se escribe en TX: RX la sigue y se ponen banda y modo al momento. */
    private void alEscribirEnTx() {
        ultimaCifraEscritaMs = System.currentTimeMillis();
        if (rxVinculada) app.txtFreqRx.setText(app.txtFreqTx.getText());
        long hz = herciosDeCampo(app.txtFreqTx);
        if (hz <= 0) return;
        String modoAntes = app.cbModo.getSelectedItem() == null ? "" : app.cbModo.getSelectedItem().toString();
        ajustarBandaYModo(hz);
        if (app.catConectado) {
            // Con CAT, la emisora sigue a lo que escribes (medio segundo después de la última cifra)
            String modoAhora = app.cbModo.getSelectedItem() == null ? "" : app.cbModo.getSelectedItem().toString();
            if (!modoAhora.equals(modoAntes)) modoPendienteEmisora = modoAhora;
            hzPendienteEmisora = hz;
            if (envioFrecuenciaEscrita == null) {
                envioFrecuenciaEscrita = new javax.swing.Timer(500, e -> {
                    final int hzEnvio = (int) hzPendienteEmisora;
                    final String modo = modoPendienteEmisora;
                    modoPendienteEmisora = null;
                    int codigo = modo == null ? 0 : codigoModoOmniRig(modo, hzEnvio);
                    enviarOrden(String.format(java.util.Locale.GERMANY, "QSY %,.3f kHz", hzEnvio / 1000.0), rig -> {
                        ponerFrecuenciaEmisora(rig, hzEnvio);
                        if (codigo != 0) Dispatch.put(rig, "Mode", new Variant(codigo));
                    });
                });
                envioFrecuenciaEscrita.setRepeats(false);
            }
            envioFrecuenciaEscrita.restart();
        }
    }

    javax.swing.Timer envioFrecuenciaEscrita;
    boolean frecuenciaPorPrograma = false;   // la frecuencia la pone el programa (banda, actividad), no la escribe el usuario
    private volatile long ultimaTeclaTxMs = 0;       // última tecla pulsada en la casilla TX
    private volatile long focoTxMs = 0;              // cuándo entró el cursor en la casilla TX
    volatile long ultimaCifraEscritaMs = 0;
    private volatile long hzPendienteEmisora;
    private volatile String modoPendienteEmisora;

    /** Al terminar de escribir (Intro o salir del campo): banda y modo según la frecuencia. */
    private boolean confirmarFrecuenciaManual(JTextField campo) {
        long hz = herciosDeCampo(campo);
        if (hz < 0) return false;
        if (campo == app.txtFreqTx) {
            if (rxVinculada) app.txtFreqRx.setText(app.txtFreqTx.getText());
            return ajustarBandaYModo(hz);
        }
        return false;
    }

    /** Ancho justo para las 11 casillas ___.___.___ (y que nunca se encoja hasta desaparecer). */
    private static void fijarAnchoDisplay(JTextField t) {
        FontMetrics fm = t.getFontMetrics(t.getFont());
        Insets in = t.getInsets();
        Dimension d = new Dimension(fm.charWidth('0') * 11 + in.left + in.right + 4, fm.getHeight() + in.top + in.bottom + 2);
        t.setPreferredSize(d);
        t.setMinimumSize(d);
    }

    /** Campo de frecuencia con aspecto de pantalla de emisora (se puede escribir en él). */
    private static void estiloCampoLcd(JTextField t, int tamLetra) {
        t.setFont(new Font("Monospaced", Font.BOLD, tamLetra));
        t.setForeground(LibroGuardiaUI.TEMA_AMBAR);
        t.setBackground(LibroGuardiaUI.TEMA_CAMPO);
        t.setCaretColor(LibroGuardiaUI.TEMA_AMBAR);
        t.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        t.setOpaque(false);
    }

    /** Pantalla grande: frecuencia TX y RX (escribibles), banda y modo, y estado de la emisora. */
    JPanel crearPantallaLcd() {
        JPanel lcd = new JPanel(new GridBagLayout());
        lcd.setBackground(app.TEMA_CAMPO);
        lcd.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(app.TEMA_LINEA, 1, true),
                BorderFactory.createEmptyBorder(4, 14, 4, 14)));
        estiloCampoLcd(app.txtFreqTx, 31);
        fijarAnchoDisplay(app.txtFreqTx);
        app.txtFreqTx.setToolTipText("Frecuencia de TX. Escríbela aquí (por ejemplo 14.205.000, 14205 o 14,205). "
                + "Con el CAT conectado, al pulsar Intro la emisora cambia a esa frecuencia.");
        estiloCampoLcd(app.txtFreqRx, 31);
        fijarAnchoDisplay(app.txtFreqRx);
        app.txtFreqRx.setToolTipText("Frecuencia de RX");
        // Con el CAT conectado, Intro en la frecuencia mueve la emisora (sin guardar el contacto)
        app.txtFreqTx.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                // Solo cifras, borrar o pegar cuentan como "escribir" (las flechas solo mueven el cursor)
                int k = e.getKeyCode();
                boolean escribe = (k >= KeyEvent.VK_0 && k <= KeyEvent.VK_9) || (k >= KeyEvent.VK_NUMPAD0 && k <= KeyEvent.VK_NUMPAD9)
                        || k == KeyEvent.VK_BACK_SPACE || k == KeyEvent.VK_DELETE || (k == KeyEvent.VK_V && e.isControlDown());
                if (escribe) ultimaTeclaTxMs = System.currentTimeMillis();
                if (e.getKeyCode() != KeyEvent.VK_ENTER) return;
                if (app.catConectado) { qsyDesdeCampo(); return; }
                // Sin CAT: Intro confirma la frecuencia (pone banda y modo). Si aún no hay indicativo, salta a
                // CALLSIGN; si ya lo hay, guarda el contacto como antes.
                confirmarFrecuenciaManual(app.txtFreqTx);
                if (app.txtIndicativo.getText().trim().isEmpty()) app.txtIndicativo.requestFocusInWindow();
                else app.guardarQSO();
            }
        });
        app.txtFreqTx.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                // RX sigue a TX si estaba vacía o era igual; si el usuario puso otra distinta, se respeta
                String rx = app.txtFreqRx.getText();
                rxVinculada = rx.isEmpty() || rx.equals(app.txtFreqTx.getText());
                focoTxMs = System.currentTimeMillis();
            }
            @Override public void focusLost(FocusEvent e) {
                // Solo si se ha escrito algo con el teclado (no al pulsar un botón de banda y luego otro botón)
                if (!app.catConectado && ultimaTeclaTxMs >= focoTxMs) confirmarFrecuenciaManual(app.txtFreqTx);
            }
        });
        app.txtFreqTx.getDocument().addDocumentListener(new DocumentListener() {
            private void cambio() {
                // Solo si lo escribe el usuario con el teclado (no cuando el programa pone la frecuencia de la emisora o de la banda)
                boolean tecleado = System.currentTimeMillis() - ultimaTeclaTxMs < 500;
                if (app.txtFreqTx.isFocusOwner() && tecleado && !frecuenciaPorPrograma) SwingUtilities.invokeLater(() -> alEscribirEnTx());
            }
            @Override public void insertUpdate(DocumentEvent e) { cambio(); }
            @Override public void removeUpdate(DocumentEvent e) { }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        app.txtFreqRx.getDocument().addDocumentListener(new DocumentListener() {
            private void cambio() {
                if (app.txtFreqRx.isFocusOwner()) { rxVinculada = false; ultimaCifraEscritaMs = System.currentTimeMillis(); }
            }
            @Override public void insertUpdate(DocumentEvent e) { cambio(); }
            @Override public void removeUpdate(DocumentEvent e) { }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        app.txtFreqRx.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { if (!app.catConectado) confirmarFrecuenciaManual(app.txtFreqRx); }
        });

        lblBandaModoLcd = new JLabel(" ");
        lblBandaModoLcd.setFont(new Font("Arial", Font.BOLD, 14));
        lblBandaModoLcd.setForeground(new Color(200, 220, 255));
        lblCtrlModo = new JLabel("CAT desconectado", SwingConstants.RIGHT);
        lblCtrlModo.setFont(new Font("Arial", Font.BOLD, 13));
        lblCtrlModo.setForeground(new Color(200, 220, 255));
        lblCtrlEstado = new JLabel(" ", SwingConstants.RIGHT);
        lblCtrlEstado.setFont(new Font("Arial", Font.PLAIN, 11));
        lblCtrlEstado.setForeground(new Color(140, 230, 140));
        app.lblAvisoCat.setForeground(new Color(255, 110, 110));
        app.lblAvisoCat.setHorizontalAlignment(SwingConstants.RIGHT);

        lblBandaModoLcd.setFont(new Font("Arial", Font.BOLD, 17));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.gridx = 0; g.gridy = 0;
        lcd.add(rotuloLcd("TX"), g);
        g.gridy = 1;
        lcd.add(app.txtFreqTx, g);
        g.gridx = 1; g.gridy = 0;
        g.insets = new Insets(0, 28, 0, 0);
        lcd.add(rotuloLcd("RX"), g);
        g.gridy = 1;
        lcd.add(app.txtFreqRx, g);
        // Recuadro CANAL (solo en CB) con flechas arriba/abajo para cambiar de canal
        g.gridx = 2; g.gridy = 0; g.gridheight = 2;
        g.insets = new Insets(0, 22, 0, 0);
        lcd.add(crearRecuadroCanal(), g);
        g.gridheight = 1;
        g.gridx = 3; g.gridy = 1;
        g.insets = new Insets(0, 22, 0, 0);
        lcd.add(lblBandaModoLcd, g);
        g.gridy = 0;
        g.gridheight = 2;
        JPanel colEstado = new JPanel();
        colEstado.setOpaque(false);
        colEstado.setLayout(new BoxLayout(colEstado, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{lblCtrlModo, lblCtrlEstado, app.lblAvisoCat}) {
            c.setAlignmentX(Component.RIGHT_ALIGNMENT);
            colEstado.add(c);
            colEstado.add(Box.createVerticalStrut(3));
        }
        // Cartel ON AIR (cluster): dentro del display, a la derecha de banda y modo
        app.cluster.cartelOnAir = new Cluster.CartelOnAir();
        app.cluster.cartelOnAir.setToolTipText("ON AIR: avisa al Cluster de Bitácora 26 de que estás en el aire");
        app.cluster.cartelOnAir.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e)) app.cluster.alternarOnAir(); }
        });
        g.gridx = 4; g.gridy = 0; g.gridheight = 2; g.weightx = 0; g.anchor = GridBagConstraints.CENTER;
        g.insets = new Insets(0, 26, 0, 0);
        lcd.add(app.cluster.cartelOnAir, g);
        g.gridx = 5; g.weightx = 1.0; g.anchor = GridBagConstraints.EAST;
        g.insets = new Insets(0, 10, 0, 0);
        lcd.add(colEstado, g);
        panelControlEmisora = lcd;
        return lcd;
    }

    static final java.util.concurrent.ExecutorService hiloCluster = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Cluster");
        t.setDaemon(true);
        return t;
    });

    /** Botón de la botonera, con letras verdes cuando está encendido. */
    JToggleButton botonSeleccion(String texto) {
        JToggleButton t = new JToggleButton(texto);
        estiloCtrl(t);
        t.setFont(new Font("Arial", Font.BOLD, 12));
        t.setMargin(new Insets(3, 1, 3, 1));
        t.addItemListener(e -> t.setForeground(t.isSelected() ? VERDE_ENCENDIDO : UIManager.getColor("Button.foreground")));
        return t;
    }

    /** Bandas, modos, VFO, SPLIT, RIT y XIT. Banda y modo sirven siempre (rellenan el contacto); el resto necesita CAT. */
    JPanel crearBotoneraEmisora() {
        JPanel radio = new JPanel();
        radio.setOpaque(false);
        radio.setLayout(new BoxLayout(radio, BoxLayout.Y_AXIS));

        String[] bandas = {"160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "2m", "70cm", "CB"};
        JPanel gBandas = new JPanel(new GridLayout(2, 7, 3, 3));
        gBandas.setOpaque(false);
        for (String b : bandas) {
            JToggleButton t = botonSeleccion(b);
            t.addActionListener(e -> elegirBanda(b));
            botonesBanda.put(b, t);
            gBandas.add(t);
        }
        JPanel gModos = new JPanel(new GridLayout(1, 7, 3, 3));
        gModos.setOpaque(false);
        for (String m : new String[]{"LSB", "USB", "CW", "SSB", "AM", "FM"}) {
            JToggleButton t = botonSeleccion(m);
            t.addActionListener(e -> elegirModo(m));
            botonesModo.put(m, t);
            gModos.add(t);
        }
        // DIGITALES: abre el programa de FT8/FT4 (WSJT-X). Con el botón derecho se elige otro programa.
        app.btnDigitales = new JButton("DIGITALES");
        estiloCtrl(app.btnDigitales);
        app.btnDigitales.setFont(new Font("Arial", Font.BOLD, 12));
        app.btnDigitales.setMargin(new Insets(3, 1, 3, 1));
        app.btnDigitales.setToolTipText("Abre la ventana DIGITALES (FT8 / FT4). Botón derecho: usar WSJT-X u otro programa");
        app.btnDigitales.addActionListener(e -> VentanaDigitales.abrir(app));
        app.btnDigitales.addMouseListener(new MouseAdapter() {
            @Override public void mouseReleased(MouseEvent e) {
                if (!SwingUtilities.isRightMouseButton(e)) return;
                JPopupMenu menu = new JPopupMenu();
                JMenuItem abrir = new JMenuItem("Abrir WSJT-X (u otro programa de digitales)");
                abrir.addActionListener(x -> app.abrirProgramaDigitales(false));
                JMenuItem elegir = new JMenuItem("Elegir el programa externo…");
                elegir.addActionListener(x -> app.abrirProgramaDigitales(true));
                menu.add(abrir);
                menu.add(elegir);
                menu.show(app.btnDigitales, e.getX(), e.getY());
            }
        });
        gModos.add(app.btnDigitales);
        app.vigilarProgramaDigitales();

        JButton bA = botonCtrl("VFO A"), bB = botonCtrl("VFO B"), bIg = botonCtrl("A=B"), bInt = botonCtrl("A↔B");
        btnVfoA = bA;
        btnVfoB = bB;
        // La FT-891 no tiene "elegir VFO": se cambia con A↔B (como la tecla A/B de la emisora)
        // FT-891: no tiene orden para elegir VFO, solo para cambiar (SV, como la tecla A/B).
        // Se pregunta la frecuencia del VFO A (FA) y, si no es el que se pide, se cambia.
        bA.addActionListener(e -> {
            if (esFt891EnUso()) { pedirVfoFt891('A'); return; }
            ordenPropiedad("VFO A", "Vfo", PM_VFOAA);
        });
        bB.addActionListener(e -> {
            if (esFt891EnUso()) { pedirVfoFt891('B'); return; }
            ordenPropiedad("VFO B", "Vfo", PM_VFOBB);
        });
        bIg.addActionListener(e -> {
            if (esFt891EnUso()) { ordenDirecta("A = B", "AB;"); return; }
            ordenPropiedad("A = B", "Vfo", PM_VFOEQUAL);
        });
        bInt.addActionListener(e -> {
            if (esFt891EnUso()) {
                ordenDirecta("A ↔ B", "SV;");
                marcarVfo(!vfoB891);
                return;
            }
            ordenPropiedad("A ↔ B", "Vfo", PM_VFOSWAP);
        });
        tgSplit = toggleCtrl("SPLIT");
        tgRit = toggleCtrl("RIT");
        tgXit = toggleCtrl("XIT");
        tgSplit.addActionListener(e -> {
            tgSplit.putClientProperty("usadoMs", System.currentTimeMillis());
            if (esFt891EnUso()) { ordenDirecta(tgSplit.isSelected() ? "Split ON" : "Split OFF", tgSplit.isSelected() ? "ST1;" : "ST0;"); return; }
            ordenPropiedad(tgSplit.isSelected() ? "Split ON" : "Split OFF", "Split", tgSplit.isSelected() ? PM_SPLITON : PM_SPLITOFF);
        });
        tgRit.addActionListener(e -> {
            tgRit.putClientProperty("usadoMs", System.currentTimeMillis());
            if (esFt891EnUso()) { ordenClarFt891(tgRit.isSelected() ? "RIT ON" : "RIT OFF"); return; }
            ordenPropiedad(tgRit.isSelected() ? "RIT ON" : "RIT OFF", "Rit", tgRit.isSelected() ? PM_RITON : PM_RITOFF);
        });
        tgXit.addActionListener(e -> {
            tgXit.putClientProperty("usadoMs", System.currentTimeMillis());
            if (esFt891EnUso()) { ordenClarFt891(tgXit.isSelected() ? "XIT ON" : "XIT OFF"); return; }
            ordenPropiedad(tgXit.isSelected() ? "XIT ON" : "XIT OFF", "Xit", tgXit.isSelected() ? PM_XITON : PM_XITOFF);
        });
        JPanel gVfo = new JPanel(new GridLayout(1, 7, 3, 3));
        gVfo.setOpaque(false);
        for (AbstractButton b : new AbstractButton[]{bA, bB, bIg, bInt, tgSplit, tgRit, tgXit}) {
            b.setFont(new Font("Arial", Font.BOLD, 12));
            b.setMargin(new Insets(3, 1, 3, 1));
            gVfo.add(b);
            controlesSoloCat.add(b);
        }

        JSpinner spOffset = new JSpinner(new SpinnerNumberModel(0, -9990, 9990, 10));
        ((JSpinner.DefaultEditor) spOffset.getEditor()).getTextField().setColumns(5);
        spOffset.setToolTipText("Desplazamiento del RIT/XIT en Hz. Al cambiarlo (flechas o escribiendo y pulsando Intro) la emisora cambia sola.");
        spOffsetRit = spOffset;
        // Se envía solo, medio segundo después del último cambio (para no mandar una orden por cada clic)
        javax.swing.Timer envioOffset = new javax.swing.Timer(500, ev -> {
            int hz = (Integer) spOffset.getValue();
            if (esFt891EnUso()) {   // FT-891: a 0 y luego subir o bajar lo que haga falta
                ordenDirecta("RIT/XIT " + (hz > 0 ? "+" : "") + hz + " Hz",
                        "RC;" + (hz == 0 ? "" : String.format(hz > 0 ? "RU%04d;" : "RD%04d;", Math.abs(hz))));
                return;
            }
            enviarOrden("RIT/XIT " + (hz > 0 ? "+" : "") + hz + " Hz", rig -> ajustarRitOffset(rig, hz));
        });
        envioOffset.setRepeats(false);
        spOffset.addChangeListener(ev -> {
            if (actualizandoOffsetDesdeEmisora || !app.catConectado) return;
            ultimoCambioOffsetMs = System.currentTimeMillis();
            envioOffset.restart();
        });
        JButton rClear = botonCtrl("A 0");
        rClear.setToolTipText("Pone el desplazamiento del RIT/XIT a 0");
        rClear.addActionListener(e -> {
            if (esFt891EnUso()) { ordenDirecta("RIT/XIT a 0", "RC;"); return; }
            enviarOrden("RIT/XIT a 0", rig -> Dispatch.call(rig, "ClearRit"));
        });
        // Aspecto de display de emisora: sin flechas, cifras grandes en verde sobre fondo oscuro, con − y + a los lados
        spOffset.setUI(new javax.swing.plaf.basic.BasicSpinnerUI() {
            @Override protected Component createNextButton() { return null; }
            @Override protected Component createPreviousButton() { return null; }
        });
        spOffset.setOpaque(false);
        spOffset.setBorder(BorderFactory.createEmptyBorder());
        JFormattedTextField campoOffset = ((JSpinner.DefaultEditor) spOffset.getEditor()).getTextField();
        campoOffset.setColumns(6);
        campoOffset.setHorizontalAlignment(JTextField.CENTER);
        campoOffset.setFont(new Font(Font.MONOSPACED, Font.BOLD, 15));
        campoOffset.setForeground(VERDE_ENCENDIDO);
        campoOffset.setCaretColor(VERDE_ENCENDIDO);
        campoOffset.setBackground(new Color(10, 16, 26));
        campoOffset.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 90, 120)), BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        JButton rMenos = botonCtrl("−"), rMas = botonCtrl("+");
        rMenos.setToolTipText("Bajar el RIT/XIT 10 Hz");
        rMas.setToolTipText("Subir el RIT/XIT 10 Hz");
        rMenos.addActionListener(e -> spOffset.setValue(Math.max(-9990, (Integer) spOffset.getValue() - 10)));
        rMas.addActionListener(e -> spOffset.setValue(Math.min(9990, (Integer) spOffset.getValue() + 10)));
        JLabel lblHz = rotuloLcd("Hz");
        JLabel lblRitXit = rotuloLcd("RIT / XIT");
        lblRitXit.setFont(new Font("Arial", Font.BOLD, 12));
        JPanel gOffset = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        gOffset.setOpaque(false);
        gOffset.add(lblRitXit);
        gOffset.add(rMenos);
        gOffset.add(spOffset);
        gOffset.add(lblHz);
        gOffset.add(rMas);
        gOffset.add(Box.createHorizontalStrut(6));
        gOffset.add(rClear);
        for (JButton b : new JButton[]{rMenos, rMas, rClear}) b.setPreferredSize(new Dimension(b == rClear ? 46 : 32, 26));
        gOffset.setMaximumSize(new Dimension(Integer.MAX_VALUE, gOffset.getPreferredSize().height));
        controlesSoloCat.addAll(java.util.List.of(spOffset, rClear, rMenos, rMas));

        for (JComponent c : new JComponent[]{rotuloSeccion("BANDA"), gBandas, (JComponent) Box.createVerticalStrut(5),
                rotuloSeccion("MODO"), gModos, (JComponent) Box.createVerticalStrut(5),
                rotuloSeccion("VFO · SPLIT · RIT / XIT"), gVfo, (JComponent) Box.createVerticalStrut(10), gOffset,
                (JComponent) Box.createVerticalStrut(6)}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            radio.add(c);
        }

        // Refresca lo que se ve: banda/modo encendidos, bandas/modos permitidos y estado del CAT
        new javax.swing.Timer(300, e -> refrescarFrontal()).start();
        refrescarFrontal();
        return radio;
    }

    private boolean ultimoCatVisto = true;
    private JSpinner spOffsetRit;
    private boolean actualizandoOffsetDesdeEmisora = false;
    private volatile long ultimoCambioOffsetMs = 0;

    void refrescarFrontal() {
        if (app.cbBanda == null || app.cbModo == null) return;
        refrescarCanalesCB();
        if (lblModeloEmisora != null) {
            String m = !app.catConectado ? "" : (modeloRig == null || modeloRig.isEmpty() || "NONE".equalsIgnoreCase(modeloRig.trim()) ? "" : modeloRig);
            if (m.startsWith("TS") || m.startsWith("TH")) m = "Kenwood " + m;
            else if (m.startsWith("FT")) m = "Yaesu " + m;
            else if (m.startsWith("IC")) m = "Icom " + m;
            EmisoraConfig activaLbl = emisoraActiva();
            if (activaLbl != null) m = "";   // el nombre ya está en la pestaña de la emisora
            if (!m.equals(lblModeloEmisora.getText())) lblModeloEmisora.setText(m);
        }
        String banda = app.cbBanda.getSelectedItem() == null ? "" : app.cbBanda.getSelectedItem().toString();
        String modo = app.cbModo.getSelectedItem() == null ? "" : app.cbModo.getSelectedItem().toString();
        for (Map.Entry<String, AbstractButton> en : botonesBanda.entrySet()) {
            en.getValue().setSelected(en.getKey().equals(banda));
            en.getValue().setEnabled(comboContiene(app.cbBanda, en.getKey()));
        }
        for (Map.Entry<String, AbstractButton> en : botonesModo.entrySet()) {
            en.getValue().setSelected(en.getKey().equals(modo));
            en.getValue().setEnabled(comboContiene(app.cbModo, en.getKey()));
        }
        if (lblBandaModoLcd != null) {
            String t = (banda.isEmpty() ? "—" : banda) + "  ·  " + (modo.isEmpty() ? "—" : modo);
            if (!t.equals(lblBandaModoLcd.getText())) lblBandaModoLcd.setText(t);
        }
        if (ultimoCatVisto != app.catConectado) {
            ultimoCatVisto = app.catConectado;
            if (!reconectandoCat) rehacerPestanasEmisora();   // las pestañas de emisora solo con el CAT conectado
            for (JComponent c : controlesSoloCat) c.setEnabled(app.catConectado);
            if (!app.catConectado) {
                lblCtrlModo.setText("CAT desconectado");
                lblCtrlEstado.setText(" ");
                if (tgSplit != null) tgSplit.setSelected(false);
                if (tgRit != null) tgRit.setSelected(false);
                if (tgXit != null) tgXit.setSelected(false);
                modeloRig = null;
            }
        }
    }

    static boolean comboContiene(JComboBox<String> combo, String valor) {
        for (int k = 0; k < combo.getItemCount(); k++) if (valor.equals(combo.getItemAt(k))) return true;
        return false;
    }

    // ======================= CANALES DE BANDA CIUDADANA =======================
    /** Los 40 canales (CEPT/FCC). Ojo: el 23 va por encima del 24 y el 25. */
    private static final int[] CANALES_CB_HZ = {
            26_965_000, 26_975_000, 26_985_000, 27_005_000, 27_015_000, 27_025_000, 27_035_000, 27_055_000,
            27_065_000, 27_075_000, 27_085_000, 27_105_000, 27_115_000, 27_125_000, 27_135_000, 27_155_000,
            27_165_000, 27_175_000, 27_185_000, 27_205_000, 27_215_000, 27_225_000, 27_255_000, 27_235_000,
            27_245_000, 27_265_000, 27_275_000, 27_285_000, 27_295_000, 27_305_000, 27_315_000, 27_325_000,
            27_335_000, 27_345_000, 27_355_000, 27_365_000, 27_375_000, 27_385_000, 27_395_000, 27_405_000};

    private JPanel panelCanalLcd;
    private JLabel lblCanalLcd;
    private final JButton[] botonesCanal = new JButton[40];
    private JToggleButton tabCanales, tabMapa;
    private CardLayout cartasDerecha;
    private JPanel contenidoDerecha;
    /** Altura de la zona de la derecha (mapa, canales CB) en el LOG: la misma en todas las actividades. */
    int alturaZonaDerecha = leerAlturaZonaDerecha();

    private static int leerAlturaZonaDerecha() {
        try { int h = Integer.parseInt(LibroGuardiaUI.ConfigApp.get("ALTURA_ZONA_DERECHA", "0")); return h > 150 && h < 600 ? h : 0; } catch (Exception e) { return 0; }
    }
    private boolean estabaEnCB = false;

    /** Número de canal (1..40) de una frecuencia, o 0 si no es de ningún canal. */
    private static int canalDeFrecuencia(long hz) {
        for (int k = 0; k < CANALES_CB_HZ.length; k++) if (Math.abs(CANALES_CB_HZ[k] - hz) <= 1000) return k + 1;
        return 0;
    }

    boolean esBandaCB() {
        return app.cbBanda != null && "CB".equalsIgnoreCase(String.valueOf(app.cbBanda.getSelectedItem()));
    }

    // ---- Cascada: botón, panel y ajustes ----
    LibroGuardiaUI.PanelCascada panelCascada;
    private LibroGuardiaUI.LectorRtlTcp lectorCascada;
    JToggleButton btnCascada;
    private String textoFiCascada;
    private final java.util.concurrent.ConcurrentLinkedQueue<float[]> colaLineasCascada = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private long ultimaLineaNs = 0;
    private long ultimoCentroCascada = 0;

    /** Crea el panel de la cascada (oculto hasta que se pulsa el botón "Cascada"). */
    LibroGuardiaUI.PanelCascada crearCascada() {
        panelCascada = new LibroGuardiaUI.PanelCascada();
        panelCascada.setAncho(LibroGuardiaUI.ConfigApp.getInt("CASCADA_ANCHO", 20) * 1000.0);
        panelCascada.alPinchar = this::irAFrecuenciaDesdeCascada;
        panelCascada.alDerecho = this::menuCalibrarCascada;
        // Rueda encima de la cascada: como el dial (cada clic, 10 pasos: 100 Hz en SSB y CW; en CB, un canal)
        panelCascada.alRueda = giro -> moverDial(giro * 10);
        // Ctrl + rueda: acercar o alejar
        panelCascada.alZoom = giro -> {
            int[] anchos = {10, 20, 30, 50, 60, 80};
            int act = (int) Math.round(panelCascada.anchoHz() / 1000), i = 0;
            while (i < anchos.length - 1 && anchos[i] < act) i++;
            i = Math.max(0, Math.min(anchos.length - 1, i - giro));   // hacia arriba = acercar (menos kHz)
            panelCascada.setAncho(anchos[i] * 1000.0);
            LibroGuardiaUI.ConfigApp.putInt("CASCADA_ANCHO", anchos[i]);
            panelCascada.reiniciar();
        };
        // El reloj que pinta la cascada solo funciona mientras llegan líneas: sin cascada, el programa descansa
        final javax.swing.Timer[] relojCascada = {null};
        lectorCascada = new LibroGuardiaUI.LectorRtlTcp(panelCascada::anchoUtil, panelCascada::anchoHz, new LibroGuardiaUI.LectorRtlTcp.Receptor() {
            @Override public void linea(float[] db) {
                // Las líneas se guardan en una cola y se pintan a ritmo constante (aunque lleguen varias juntas)
                colaLineasCascada.offer(db);
                while (colaLineasCascada.size() > 12) colaLineasCascada.poll();   // sin acumular retraso
                javax.swing.Timer r = relojCascada[0];
                if (r != null && !r.isRunning()) SwingUtilities.invokeLater(() -> { if (!r.isRunning()) r.start(); });
            }
            @Override public void estado(String texto) {
                SwingUtilities.invokeLater(() -> {
                    panelCascada.setAviso(texto);
                    if (lectorCascada != null && !lectorCascada.activo() && btnCascada != null && texto != null
                            && !texto.startsWith("Conectando") && !texto.startsWith("Esperando")) {
                        app.lblEstado.setText("Cascada: " + texto);
                    }
                });
            }
        });
        final int[] vaciosSeguidos = {0};
        javax.swing.Timer ritmo = new javax.swing.Timer(10, e -> {
            long ahora = System.nanoTime();
            int enCola = colaLineasCascada.size();
            if (enCola == 0) {
                // 2 segundos sin líneas (cascada cerrada o sin receptor): se para hasta que vuelvan a llegar
                if (++vaciosSeguidos[0] > 200) { vaciosSeguidos[0] = 0; ((javax.swing.Timer) e.getSource()).stop(); }
                return;
            }
            vaciosSeguidos[0] = 0;
            // Intervalo entre líneas según la velocidad; si se acumulan, se va un poco más deprisa
            double intervalo = 1e9 / (LibroGuardiaUI.LectorRtlTcp.MUESTREO / (double) LibroGuardiaUI.LectorRtlTcp.SALTO / Math.max(1, LibroGuardiaUI.LectorRtlTcp.mediaCascada));
            if (enCola > 4) intervalo *= 0.6;
            if (ahora - ultimaLineaNs < intervalo) return;
            ultimaLineaNs = ahora;
            float[] db = colaLineasCascada.poll();
            if (db != null) panelCascada.nuevaLinea(db);
        });
        ritmo.setCoalesce(true);
        relojCascada[0] = ritmo;
        ritmo.start();
        // La escala sigue a la frecuencia de la emisora (o a la escrita en el contacto)
        new javax.swing.Timer(150, e -> {
            if (!panelCascada.isShowing()) return;
            long hz = herciosDeCampo(app.txtFreqTx);
            if (hz > 0 && hz != ultimoCentroCascada) colaLineasCascada.clear();   // lo de la frecuencia anterior ya no vale
            ultimoCentroCascada = hz;
            panelCascada.setCentro(hz > 0 ? hz : 0);
            String modoCas = app.cbModo.getSelectedItem() == null ? "" : app.cbModo.getSelectedItem().toString();
            panelCascada.setModo(modoCas);
            LibroGuardiaUI.LectorRtlTcp.ajusteHz = ajusteCascada(modoCas);
            EmisoraConfig em = emisoraActiva();
            if (textoFiCascada == null)   // se lee una vez (leer la configuración del disco cada poco frena)
                textoFiCascada = String.format(java.util.Locale.GERMANY, "%,.3f", Long.parseLong(LibroGuardiaUI.ConfigApp.get("CASCADA_FI", "8830000").trim()) / 1e6);
            String fi = textoFiCascada;
            panelCascada.setPie((em != null ? em.nombre + " · " : "") + "FI " + fi + " MHz · ±"
                    + (int) (panelCascada.anchoHz() / 2000) + " kHz"
                    + (LibroGuardiaUI.LectorRtlTcp.ajusteHz != 0 ? String.format(java.util.Locale.GERMANY, " · calibrado %s %+,d Hz", modoCas, LibroGuardiaUI.LectorRtlTcp.ajusteHz) : "")
                    + (app.catConectado ? "" : " · sin CAT: la escala es la del contacto"));
        }).start();
        panelCascada.setVisible(false);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { if (lectorCascada != null) lectorCascada.parar(); }));
        // Al cerrar el programa con ON AIR encendido, se avisa al cluster de que ya no se está en el aire
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.cluster.apagarAlCerrar();
        }));
        return panelCascada;
    }

    // ---- Calibración de la cascada: dónde cae la portadora en la FI según el modo ----
    private final Map<String, Integer> ajustesCascada = new HashMap<>();

    private int ajusteCascada(String modo) {
        String m = modo == null ? "" : modo.trim().toUpperCase();
        if (m.isEmpty()) return 0;
        return ajustesCascada.computeIfAbsent(m, k -> LibroGuardiaUI.ConfigApp.getInt("CASCADA_AJUSTE_" + k, 0));
    }

    /** Botón derecho en la cascada: "la portadora de esta señal está aquí" (calibrar) o quitar la calibración. */
    private void menuCalibrarCascada(MouseEvent e, long hzRaton) {
        String modo = app.cbModo.getSelectedItem() == null ? "" : app.cbModo.getSelectedItem().toString().trim().toUpperCase();
        long centro = herciosDeCampo(app.txtFreqTx);
        if (modo.isEmpty() || centro <= 0) return;
        JPopupMenu menu = new JPopupMenu();
        String donde = switch (modo) {
            case "LSB" -> "el borde DERECHO de la señal";
            case "USB" -> "el borde IZQUIERDO de la señal";
            default -> "el centro de la portadora";
        };
        JMenuItem calibrar = new JMenuItem("Calibrar " + modo + ": la línea roja tiene que estar aquí (" + donde + ")");
        calibrar.addActionListener(a -> {
            int nuevo = ajusteCascada(modo) + (int) (hzRaton - centro);
            if (Math.abs(nuevo) > 5000) {
                JOptionPane.showMessageDialog(app, "Eso son más de 5 kHz de diferencia: seguramente no es la misma señal.\n"
                        + "Sintoniza la señal en la emisora hasta que se oiga bien y vuelve a probar.", "Calibrar la cascada", JOptionPane.WARNING_MESSAGE);
                return;
            }
            ajustesCascada.put(modo, nuevo);
            LibroGuardiaUI.ConfigApp.putInt("CASCADA_AJUSTE_" + modo, nuevo);
            LibroGuardiaUI.LectorRtlTcp.ajusteHz = nuevo;
            panelCascada.reiniciar();
            app.lblEstado.setText(String.format(java.util.Locale.GERMANY, "Cascada calibrada en %s: %+,d Hz", modo, nuevo));
        });
        menu.add(calibrar);
        if (Math.abs(LibroGuardiaUI.PanelCascada.vistaHz) >= 1) {
            JMenuItem centrar = new JMenuItem("Volver a centrar la cascada en la frecuencia de la emisora");
            centrar.addActionListener(a -> { LibroGuardiaUI.PanelCascada.vistaHz = 0; panelCascada.repaint(); });
            menu.add(centrar);
        }
        if (ajusteCascada(modo) != 0) {
            JMenuItem quitar = new JMenuItem("Quitar la calibración de " + modo);
            quitar.addActionListener(a -> {
                ajustesCascada.put(modo, 0);
                LibroGuardiaUI.ConfigApp.putInt("CASCADA_AJUSTE_" + modo, 0);
                LibroGuardiaUI.LectorRtlTcp.ajusteHz = 0;
                panelCascada.reiniciar();
                app.lblEstado.setText("Cascada: calibración de " + modo + " quitada");
            });
            menu.add(quitar);
        }
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    /** Botón "Cascada": la muestra y empieza a recibir, o la oculta y suelta el RTL-SDR. */
    void alternarCascada(boolean ver) {
        if (panelCascada == null) return;
        panelCascada.setVisible(ver);
        LibroGuardiaUI.ConfigApp.put("CASCADA_VISIBLE", ver ? "1" : "0");
        if (ver) {
            panelCascada.setAviso("Conectando con el RTL-SDR…");
            lectorCascada.arrancar();
        } else {
            lectorCascada.parar();
        }
        app.cluster.actualizarClusterIncrustado();
        app.revalidate();
        app.repaint();
    }

    /** Pinchar en la cascada: el contacto (y la emisora, con CAT) van a esa frecuencia. */
    private void irAFrecuenciaDesdeCascada(long hz) {
        if (hz < 100_000 || hz > 470_000_000) return;
        String f = String.format(java.util.Locale.GERMANY, "%,d", hz);
        boolean rxIgual = app.txtFreqRx.getText().isEmpty() || app.txtFreqRx.getText().equals(app.txtFreqTx.getText());
        ultimaCifraEscritaMs = System.currentTimeMillis();
        app.txtFreqTx.setText(f);
        if (rxIgual) app.txtFreqRx.setText(f);
        if (app.catConectado) {
            final int hzEnvio = (int) hz;
            enviarOrden(String.format(java.util.Locale.GERMANY, "QSY %,.3f kHz", hz / 1000.0), rig -> ponerFrecuenciaEmisora(rig, hzEnvio));
        }
        refrescarFrontal();
    }

    /** Mi Estación → Cascada (RTL-SDR). */
    void abrirDialogoCascada() {
        JDialog d = new JDialog(app, "Configurar Cascada (RTL-SDR)", true);
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 14));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        JTextField txtFi = new JTextField(LibroGuardiaUI.ConfigApp.get("CASCADA_FI", "8830000"), 12);
        JCheckBox chkInv = new JCheckBox("La FI sale invertida (las señales se mueven al revés)", "1".equals(LibroGuardiaUI.ConfigApp.get("CASCADA_INVERTIR", "1")));
        JComboBox<String> cbAncho = new JComboBox<>(new String[]{"±5 kHz", "±10 kHz", "±15 kHz", "±25 kHz", "±30 kHz", "±40 kHz"});
        int[] anchos = {10, 20, 30, 50, 60, 80};
        int anchoAct = LibroGuardiaUI.ConfigApp.getInt("CASCADA_ANCHO", 20);
        for (int i = 0; i < anchos.length; i++) if (anchos[i] == anchoAct) cbAncho.setSelectedIndex(i);
        JComboBox<String> cbVel = new JComboBox<>(new String[]{"Rápida", "Normal", "Lenta"});
        int[] medias = {1, 2, 4};
        int mediaAct = LibroGuardiaUI.ConfigApp.getInt("CASCADA_MEDIA", 2);
        for (int i = 0; i < medias.length; i++) if (medias[i] == mediaAct) cbVel.setSelectedIndex(i);
        int ganAct = LibroGuardiaUI.ConfigApp.getInt("CASCADA_GANANCIA", LibroGuardiaUI.LectorRtlTcp.GANANCIA_CASCADA_INICIAL);
        JCheckBox chkAuto = new JCheckBox("Ganancia automática", ganAct < 0);
        JSlider slGan = new JSlider(0, 496, ganAct < 0 ? LibroGuardiaUI.LectorRtlTcp.GANANCIA_CASCADA_INICIAL : ganAct);
        // Contraste: cuánto destacan las señales sobre el ruido (se aplica al momento)
        JSlider slContraste = new JSlider(0, 100, LibroGuardiaUI.PanelCascada.contraste);
        JLabel lblContraste = new JLabel();
        Runnable verContraste = () -> lblContraste.setText(slContraste.getValue() + " %");
        slContraste.addChangeListener(e -> { verContraste.run(); LibroGuardiaUI.PanelCascada.contraste = slContraste.getValue(); });
        verContraste.run();
        final int contrasteAntes = LibroGuardiaUI.PanelCascada.contraste;
        JLabel lblGan = new JLabel();
        Runnable verGan = () -> {
            slGan.setEnabled(!chkAuto.isSelected());
            lblGan.setText(chkAuto.isSelected() ? "auto" : String.format(java.util.Locale.GERMANY, "%.1f dB", slGan.getValue() / 10.0));
        };
        slGan.addChangeListener(e -> verGan.run());
        chkAuto.addActionListener(e -> verGan.run());
        verGan.run();
        JSpinner spPpm = new JSpinner(new SpinnerNumberModel(LibroGuardiaUI.ConfigApp.getInt("CASCADA_PPM", 0), -100, 100, 1));
        File encontrado = LibroGuardiaUI.LectorRtlTcp.buscarRtlTcp();
        JTextField txtRuta = new JTextField(LibroGuardiaUI.ConfigApp.get("RTL_TCP_RUTA", encontrado == null ? "" : encontrado.getAbsolutePath()), 28);
        JButton btnExaminar = new JButton("Examinar…");
        btnExaminar.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(txtRuta.getText().isEmpty() ? null : new File(txtRuta.getText()).getParentFile());
            fc.setAcceptAllFileFilterUsed(false);
            fc.setFileFilter(new javax.swing.filechooser.FileFilter() {   // solo rtl_tcp.exe (no HDSDR ni otros programas)
                @Override public boolean accept(File f) { return f.isDirectory() || LibroGuardiaUI.LectorRtlTcp.esRtlTcp(f); }
                @Override public String getDescription() { return "rtl_tcp.exe"; }
            });
            if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION) {
                if (LibroGuardiaUI.LectorRtlTcp.esRtlTcp(fc.getSelectedFile())) txtRuta.setText(fc.getSelectedFile().getAbsolutePath());
                else JOptionPane.showMessageDialog(d, "Hay que elegir el archivo rtl_tcp.exe (está en la carpeta rtl-sdr del programa),\n"
                        + "no HDSDR ni otro programa.", "Programa rtl_tcp", JOptionPane.WARNING_MESSAGE);
            }
        });
        int y = 0;
        g.gridx = 0; g.gridy = y; p.add(new JLabel("Frecuencia intermedia (FI) de la emisora, en Hz:"), g);
        g.gridx = 1; p.add(txtFi, g);
        g.gridx = 0; g.gridy = ++y; g.gridwidth = 2; p.add(chkInv, g); g.gridwidth = 1;
        g.gridx = 0; g.gridy = ++y; p.add(new JLabel("Ancho que se ve:"), g);
        g.gridx = 1; p.add(cbAncho, g);
        g.gridx = 0; g.gridy = ++y; p.add(new JLabel("Velocidad de la cascada:"), g);
        g.gridx = 1; p.add(cbVel, g);
        g.gridx = 0; g.gridy = ++y; p.add(chkAuto, g);
        JPanel filaGan = new JPanel(new BorderLayout(6, 0));
        filaGan.add(slGan, BorderLayout.CENTER);
        filaGan.add(lblGan, BorderLayout.EAST);
        g.gridx = 1; p.add(filaGan, g);
        g.gridx = 0; g.gridy = ++y; p.add(new JLabel("Contraste (cuánto destacan las señales):"), g);
        JPanel filaCon = new JPanel(new BorderLayout(6, 0));
        filaCon.add(slContraste, BorderLayout.CENTER);
        filaCon.add(lblContraste, BorderLayout.EAST);
        g.gridx = 1; p.add(filaCon, g);
        g.gridx = 0; g.gridy = ++y; p.add(new JLabel("Corrección de frecuencia del RTL-SDR (ppm):"), g);
        g.gridx = 1; p.add(spPpm, g);
        g.gridx = 0; g.gridy = ++y; p.add(new JLabel("Programa rtl_tcp.exe:"), g);
        JPanel filaRuta = new JPanel(new BorderLayout(6, 0));
        filaRuta.add(txtRuta, BorderLayout.CENTER);
        filaRuta.add(btnExaminar, BorderLayout.EAST);
        g.gridx = 1; p.add(filaRuta, g);
        JLabel nota = new JLabel("<html><br>La Kenwood TS-870 tiene la FI en 8.830.000 Hz (la misma que usa HDSDR).<br>"
                + "Con la TS-870 y el RTL-SDR V4 la FI sale invertida: deja marcada esa casilla.<br>"
                + "El RTL-SDR solo lo puede usar un programa a la vez: cierra HDSDR antes de abrir la cascada.<br>"
                + "Si las señales se ven flojas, sube la ganancia o el contraste. Si todo se llena de color, bájalos.</html>");
        nota.setForeground(new Color(150, 170, 200));
        g.gridx = 0; g.gridy = ++y; g.gridwidth = 2; p.add(nota, g);
        JButton btnGuardar = new JButton("Guardar"), btnCancelar = new JButton("Cancelar");
        btnCancelar.addActionListener(e -> { LibroGuardiaUI.PanelCascada.contraste = contrasteAntes; d.dispose(); });
        btnGuardar.addActionListener(e -> {
            LibroGuardiaUI.ConfigApp.putInt("CASCADA_CONTRASTE", slContraste.getValue());
            String fi = txtFi.getText().replaceAll("[^0-9]", "");
            if (fi.isEmpty() || Long.parseLong(fi) < 100_000 || Long.parseLong(fi) > 1_700_000_000L) {
                JOptionPane.showMessageDialog(d, "Escribe la FI en Hz, por ejemplo 8830000.", "Aviso", JOptionPane.WARNING_MESSAGE);
                return;
            }
            LibroGuardiaUI.ConfigApp.put("CASCADA_FI", fi);
            LibroGuardiaUI.ConfigApp.put("CASCADA_INVERTIR", chkInv.isSelected() ? "1" : "0");
            LibroGuardiaUI.ConfigApp.putInt("CASCADA_ANCHO", anchos[cbAncho.getSelectedIndex()]);
            LibroGuardiaUI.ConfigApp.putInt("CASCADA_MEDIA", medias[cbVel.getSelectedIndex()]);
            LibroGuardiaUI.LectorRtlTcp.mediaCascada = medias[cbVel.getSelectedIndex()];   // se aplica al momento
            textoFiCascada = null;
            LibroGuardiaUI.ConfigApp.putInt("CASCADA_GANANCIA", chkAuto.isSelected() ? -1 : slGan.getValue());
            LibroGuardiaUI.ConfigApp.putInt("CASCADA_PPM", (Integer) spPpm.getValue());
            String ruta = txtRuta.getText().trim();
            if (!ruta.isEmpty() && !LibroGuardiaUI.LectorRtlTcp.esRtlTcp(new File(ruta))) {
                JOptionPane.showMessageDialog(d, "En \"Programa rtl_tcp.exe\" hay que poner el archivo rtl_tcp.exe\n"
                        + "(está en la carpeta rtl-sdr del programa), no HDSDR ni otro programa.\n\n"
                        + "Si lo dejas vacío, el programa lo busca solo.", "Programa rtl_tcp", JOptionPane.WARNING_MESSAGE);
                return;
            }
            LibroGuardiaUI.ConfigApp.put("RTL_TCP_RUTA", ruta);
            panelCascada.setAncho(anchos[cbAncho.getSelectedIndex()] * 1000.0);
            d.dispose();
            // Si estaba funcionando, se vuelve a arrancar con lo nuevo
            if (lectorCascada.activo()) {
                lectorCascada.parar();
                new javax.swing.Timer(1500, ev -> { ((javax.swing.Timer) ev.getSource()).stop(); if (panelCascada.isVisible()) lectorCascada.arrancar(); }).start();
            }
        });
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        botones.add(btnGuardar);
        botones.add(btnCancelar);
        d.add(p, BorderLayout.CENTER);
        d.add(botones, BorderLayout.SOUTH);
        d.pack();
        d.setLocationRelativeTo(app);
        d.setVisible(true);
    }

    // ===================== MANDO DE SINTONÍA (dial) =====================
    DialEmisora dialSintonia;
    private volatile long hzDialPendiente = -1;
    private javax.swing.Timer envioDial;
    private int pasosCanalDial = 0;

    /** Paso del dial según el modo: 10 Hz en SSB y CW, 100 Hz en AM y 1 kHz en FM. */
    private int pasoDialHz() {
        String modo = app.cbModo.getSelectedItem() == null ? "" : app.cbModo.getSelectedItem().toString();
        if (modo.equals("FM")) return 1000;
        if (modo.equals("AM")) return 100;
        return 10;
    }

    /** El dial ha girado "pasos" (positivo = a la derecha, sube la frecuencia). */
    void moverDial(int pasos) {
        if (pasos == 0) return;
        if (esBandaCB()) {
            // En CB, cada veinteavo de vuelta (o cada clic de la rueda) es un canal
            pasosCanalDial += pasos;
            int umbral = DialEmisora.PASOS_POR_VUELTA / 20;
            while (Math.abs(pasosCanalDial) >= umbral) {
                int s = pasosCanalDial > 0 ? 1 : -1;
                pasosCanalDial -= s * umbral;
                cambiarCanal(s);
            }
            return;
        }
        pasosCanalDial = 0;
        long hz = herciosDeCampo(app.txtFreqTx);
        if (hz <= 0) {
            String banda = app.cbBanda.getSelectedItem() == null ? "" : app.cbBanda.getSelectedItem().toString();
            Integer inicio = INICIO_BANDA_HZ.get(banda);
            if (inicio == null) return;
            hz = inicio;
        }
        int paso = pasoDialHz();
        long nuevo = (hz / paso) * paso + (long) pasos * paso;
        if (hz % paso != 0 && pasos < 0) nuevo += paso;   // primero se ajusta al paso redondo
        ponerFrecuenciaDial(nuevo);
    }

    /** Botón de ajuste fino: un toque mueve 10 Hz; si se deja pulsado, sigue moviendo (cada vez más rápido). */
    // ===================== SEND (transmitir con la emisora) =====================
    static final int PM_RX = 0x200000;
    private static final int SEND_MAX_SEGUNDOS = 180;   // corte automático: 3 minutos
    JButton btnSend;
    volatile boolean txPedidoSend = false;
    private javax.swing.Timer corteSend;
    private long inicioSendMs = 0;

    JButton crearBotonSend() {
        JButton b = new JButton("SEND");
        b.setUI(new BotonEmisoraUI() {
            @Override public void paint(Graphics g0, JComponent c) {
                if (!Boolean.TRUE.equals(c.getClientProperty("tx"))) { super.paint(g0, c); return; }
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = c.getWidth(), h = c.getHeight();
                g.setPaint(new GradientPaint(0, 0, new Color(230, 40, 40), 0, h, new Color(150, 10, 10)));
                g.fillRoundRect(0, 0, w - 1, h - 1, 7, 7);
                g.setColor(new Color(60, 0, 0));
                g.drawRoundRect(0, 0, w - 1, h - 1, 7, 7);
                g.setColor(Color.WHITE);
                g.setFont(c.getFont());
                FontMetrics fm = g.getFontMetrics();
                String t = ((AbstractButton) c).getText();
                g.drawString(t, (w - fm.stringWidth(t)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
                g.dispose();
            }
        });
        b.setFont(new Font("Arial", Font.BOLD, 13));
        b.setMargin(new Insets(2, 3, 2, 3));
        b.setFocusable(false);
        b.setPreferredSize(new Dimension(122, 28));
        b.setToolTipText("<html>Pone la emisora a <b>transmitir</b> (como su tecla SEND/MOX). Otra pulsación la vuelve a recepción.<br>"
                + "Por seguridad vuelve sola a recepción a los 3 minutos. Necesita el CAT conectado.</html>");
        b.addActionListener(e -> pulsarSend());
        // El botón se pone rojo mientras la emisora transmite (también si se transmite con el micro)
        new javax.swing.Timer(250, e -> {
            boolean tx = app.catConectado && (txPedidoSend || emisoraTransmitiendo);
            if (!Boolean.valueOf(tx).equals(b.getClientProperty("tx"))) {
                b.putClientProperty("tx", tx);
                b.repaint();
            }
            String txt = txPedidoSend && app.catConectado ? "TX " + Math.max(0, SEND_MAX_SEGUNDOS - (System.currentTimeMillis() - inicioSendMs) / 1000) + " s"
                    : tx ? "TX" : "SEND";
            if (!txt.equals(b.getText())) b.setText(txt);
        }).start();
        return b;
    }

    private void pulsarSend() {
        if (txPedidoSend || emisoraTransmitiendo) {
            pararSend("Recepción");
            return;
        }
        if (!app.catConectado) {
            JOptionPane.showMessageDialog(app, "Primero pulsa \"Conectar CAT\".", "SEND", JOptionPane.WARNING_MESSAGE);
            return;
        }
        txPedidoSend = true;
        inicioSendMs = System.currentTimeMillis();
        enviarOrden("Transmitiendo (SEND)", rig -> Dispatch.put(rig, "Tx", new Variant(PM_TX)));
        if (corteSend == null) {
            corteSend = new javax.swing.Timer(SEND_MAX_SEGUNDOS * 1000, e -> {
                if (!txPedidoSend) return;
                pararSend("Corte automático a los 3 minutos");
                app.lblEstado.setText("SEND: la emisora ha vuelto a recepción sola (máximo 3 minutos transmitiendo)");
            });
            corteSend.setRepeats(false);
        }
        corteSend.restart();
        app.lblEstado.setText("SEND: la emisora está transmitiendo. Pulsa otra vez para volver a recepción.");
    }

    /** La ventana DIGITALES ha puesto la emisora a transmitir (si se desconecta el CAT, se vuelve a recepción). */
    volatile boolean txDigitales = false;

    /** PTT de la ventana DIGITALES: la misma orden que el botón SEND, sin su temporizador de 3 minutos. */
    void pttDigitales(boolean transmitir) throws Exception {
        if (!app.catConectado) throw new Exception("el CAT no está conectado");
        txDigitales = transmitir;
        ordenesCat.add(rig -> Dispatch.put(rig, "Tx", new Variant(transmitir ? PM_TX : PM_RX)));
    }

    /** Vuelve la emisora a recepción. */
    void pararSend(String motivo) {
        txPedidoSend = false;
        if (corteSend != null) corteSend.stop();
        if (app.catConectado) ordenesCat.add(rig -> Dispatch.put(rig, "Tx", new Variant(PM_RX)));
        if (motivo != null && app.lblEstado != null && !motivo.startsWith("Corte")) app.lblEstado.setText("SEND: recepción");
    }

    JButton crearBotonFino(String texto, int signo) {
        JButton b = botonCtrl(texto);   // mismo aspecto que los botones de la emisora
        b.setPreferredSize(new Dimension(58, 24));
        b.setToolTipText(signo > 0 ? "Subir la frecuencia 10 Hz (déjalo pulsado para seguir subiendo)"
                : "Bajar la frecuencia 10 Hz (déjalo pulsado para seguir bajando)");
        int[] repeticiones = {0};
        javax.swing.Timer repetir = new javax.swing.Timer(80, e -> {
            repeticiones[0]++;
            moverFino(signo);
            if (repeticiones[0] > 25) moverFino(signo);   // tras 2 segundos, al doble de velocidad
        });
        repetir.setInitialDelay(450);
        b.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || !b.isEnabled()) return;
                repeticiones[0] = 0;
                moverFino(signo);
                repetir.restart();
            }
            @Override public void mouseReleased(MouseEvent e) { repetir.stop(); }
            @Override public void mouseExited(MouseEvent e) { repetir.stop(); }
        });
        return b;
    }

    /** Botones UP / DOWN: ajuste fino de 10 Hz (también en CB, para afinar el canal). */
    private void moverFino(int signo) {
        long hz = herciosDeCampo(app.txtFreqTx);
        if (hz <= 0) {
            String banda = app.cbBanda.getSelectedItem() == null ? "" : app.cbBanda.getSelectedItem().toString();
            Integer inicio = INICIO_BANDA_HZ.get(banda);
            if (inicio == null) return;
            hz = inicio;
        }
        ponerFrecuenciaDial(hz + signo * 10L);
    }

    /** Pone la frecuencia que ha dado el dial (o los botones UP / DOWN) y, con CAT, la manda a la emisora. */
    private void ponerFrecuenciaDial(long nuevo) {
        if (nuevo < 100_000 || nuevo > 470_000_000) return;
        String f = String.format(java.util.Locale.GERMANY, "%,d", nuevo);
        boolean rxIgual = app.txtFreqRx.getText().isEmpty() || app.txtFreqRx.getText().equals(app.txtFreqTx.getText());
        ultimaCifraEscritaMs = System.currentTimeMillis();   // que la lectura del CAT no lo pise mientras giras
        app.txtFreqTx.setText(f);
        if (rxIgual) app.txtFreqRx.setText(f);
        ajustarBandaYModo(nuevo, false);   // el dial solo cambia la banda; el modo lo elige el operador
        if (app.catConectado) {
            // A la emisora se le manda la última frecuencia cada décima de segundo mientras se gira
            hzDialPendiente = nuevo;
            if (envioDial == null) {
                envioDial = new javax.swing.Timer(100, e -> {
                    final int hzEnvio = (int) hzDialPendiente;
                    if (hzEnvio <= 0 || !app.catConectado) return;
                    hzDialPendiente = -1;
                    ultimaCifraEscritaMs = System.currentTimeMillis();
                    enviarOrden(String.format(java.util.Locale.GERMANY, "QSY %,.3f kHz", hzEnvio / 1000.0),
                            rig -> ponerFrecuenciaEmisora(rig, hzEnvio));
                });
                envioDial.setRepeats(false);
            }
            if (!envioDial.isRunning()) envioDial.start();
        }
        refrescarFrontal();
    }

    /**
     * Mando redondo de sintonía: se gira pinchando con el botón izquierdo y haciendo círculos con el ratón
     * (o con la rueda del ratón). Avisa de cuántos pasos se ha girado: a la derecha sube, a la izquierda baja.
     */
    static class DialEmisora extends JComponent {
        static final int PASOS_POR_VUELTA = 200;
        private final java.util.function.IntConsumer alGirar;
        private double angulo = -Math.PI / 2;   // posición del hoyuelo (arriba)
        private double anguloRaton, acumulado;
        private long ultimoMovimiento;
        private boolean girando;

        DialEmisora(java.util.function.IntConsumer alGirar) {
            this.alGirar = alGirar;
            setPreferredSize(new Dimension(128, 128));
            setMinimumSize(new Dimension(128, 128));
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Sintonía: pincha con el botón izquierdo y haz círculos (a la derecha sube). También con la rueda del ratón.");
            MouseAdapter raton = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return;
                    girando = true;
                    anguloRaton = anguloDe(e);
                    ultimoMovimiento = System.currentTimeMillis();
                    repaint();
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (!girando) return;
                    double a = anguloDe(e);
                    double d = a - anguloRaton;
                    while (d > Math.PI) d -= 2 * Math.PI;
                    while (d < -Math.PI) d += 2 * Math.PI;
                    double cx = getWidth() / 2.0, cy = getHeight() / 2.0;
                    if (Math.hypot(e.getX() - cx, e.getY() - cy) < 6) return;   // en el mismo centro no se sabe hacia dónde
                    anguloRaton = a;
                    girar(d);
                }
                @Override public void mouseReleased(MouseEvent e) { girando = false; repaint(); }
                @Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                    // Cada "clic" de la rueda: 10 pasos (hacia arriba sube)
                    girar(-e.getWheelRotation() * 10 * 2 * Math.PI / PASOS_POR_VUELTA);
                }
            };
            addMouseListener(raton);
            addMouseMotionListener(raton);
            addMouseWheelListener(raton);
        }

        private double anguloDe(MouseEvent e) {
            return Math.atan2(e.getY() - getHeight() / 2.0, e.getX() - getWidth() / 2.0);
        }

        /** d en radianes; positivo = sentido de las agujas del reloj. Si se gira deprisa, avanza más. */
        private void girar(double d) {
            long ahora = System.currentTimeMillis();
            long dt = Math.max(1, ahora - ultimoMovimiento);
            ultimoMovimiento = ahora;
            double vueltasPorSegundo = Math.abs(d) / (2 * Math.PI) / (dt / 1000.0);
            int multiplicador = vueltasPorSegundo > 2.0 ? 5 : vueltasPorSegundo > 1.0 ? 2 : 1;
            angulo += d;
            acumulado += d * PASOS_POR_VUELTA / (2 * Math.PI);
            int pasos = (int) acumulado;   // se queda con la parte entera (hacia cero)
            acumulado -= pasos;
            repaint();
            if (pasos != 0 && alGirar != null) alGirar.accept(pasos * multiplicador);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int lado = Math.min(getWidth(), getHeight()) - 4;
            double cx = getWidth() / 2.0, cy = getHeight() / 2.0, r = lado / 2.0;
            // Marcas alrededor
            g.setColor(new Color(90, 110, 140));
            for (int k = 0; k < 60; k++) {
                double a = k * 2 * Math.PI / 60;
                double r1 = r - (k % 5 == 0 ? 7 : 4), r2 = r;
                g.setStroke(new BasicStroke(k % 5 == 0 ? 1.6f : 1f));
                g.draw(new java.awt.geom.Line2D.Double(cx + Math.cos(a) * r1, cy + Math.sin(a) * r1, cx + Math.cos(a) * r2, cy + Math.sin(a) * r2));
            }
            // Faldón (anillo exterior estriado)
            double rf = r - 9;
            g.setPaint(new GradientPaint((float) (cx - rf), (float) (cy - rf), new Color(70, 76, 88),
                    (float) (cx + rf), (float) (cy + rf), new Color(18, 20, 26)));
            g.fill(new java.awt.geom.Ellipse2D.Double(cx - rf, cy - rf, 2 * rf, 2 * rf));
            g.setColor(new Color(10, 12, 16));
            g.setStroke(new BasicStroke(1.2f));
            for (int k = 0; k < 72; k++) {   // estrías que giran con el mando
                double a = angulo + k * 2 * Math.PI / 72;
                g.draw(new java.awt.geom.Line2D.Double(cx + Math.cos(a) * (rf - 6), cy + Math.sin(a) * (rf - 6), cx + Math.cos(a) * rf, cy + Math.sin(a) * rf));
            }
            // Cara del mando (metal oscuro con brillo)
            double rc = rf - 7;
            g.setPaint(new RadialGradientPaint(new java.awt.geom.Point2D.Double(cx - rc * 0.35, cy - rc * 0.4), (float) (rc * 1.5),
                    new float[]{0f, 0.45f, 1f}, new Color[]{new Color(92, 98, 110), new Color(44, 48, 58), new Color(20, 22, 28)}));
            g.fill(new java.awt.geom.Ellipse2D.Double(cx - rc, cy - rc, 2 * rc, 2 * rc));
            g.setColor(new Color(110, 118, 132));
            g.setStroke(new BasicStroke(1f));
            g.draw(new java.awt.geom.Ellipse2D.Double(cx - rc, cy - rc, 2 * rc, 2 * rc));
            // Hoyuelo para el dedo
            double rd = rc * 0.2, dd = rc * 0.58;
            double hx = cx + Math.cos(angulo) * dd, hy = cy + Math.sin(angulo) * dd;
            g.setPaint(new RadialGradientPaint(new java.awt.geom.Point2D.Double(hx + rd * 0.3, hy + rd * 0.3), (float) (rd * 1.3),
                    new float[]{0f, 1f}, new Color[]{new Color(70, 76, 88), new Color(12, 14, 18)}));
            g.fill(new java.awt.geom.Ellipse2D.Double(hx - rd, hy - rd, 2 * rd, 2 * rd));
            g.setColor(girando ? VERDE_ENCENDIDO : new Color(120, 128, 140));
            g.setStroke(new BasicStroke(girando ? 2f : 1f));
            g.draw(new java.awt.geom.Ellipse2D.Double(hx - rd, hy - rd, 2 * rd, 2 * rd));
            g.dispose();
        }
    }

    /** Pone la frecuencia de un canal en el contacto y, con CAT, en la emisora. */
    private void irACanal(int canal) {
        if (canal < 1 || canal > 40) return;
        int hz = CANALES_CB_HZ[canal - 1];
        if (!esBandaCB() && comboContiene(app.cbBanda, "CB")) {
            bandaPuestaPorFrecuencia = true;
            try { app.cbBanda.setSelectedItem("CB"); } finally { bandaPuestaPorFrecuencia = false; }
        }
        String f = String.format(java.util.Locale.GERMANY, "%,d", hz);
        app.txtFreqTx.setText(f);
        app.txtFreqRx.setText(f);
        if (app.catConectado) {
            ultimaCifraEscritaMs = System.currentTimeMillis();   // que la lectura no lo pise mientras la emisora cambia
            enviarOrden("canal " + canal, rig -> ponerFrecuenciaEmisora(rig, hz));
        }
        refrescarFrontal();
    }

    /** Canal siguiente (+1) o anterior (-1). Si la frecuencia no es de un canal, va al más cercano en ese sentido. */
    private void cambiarCanal(int sentido) {
        long hz = herciosDeCampo(app.txtFreqTx);
        int actual = hz > 0 ? canalDeFrecuencia(hz) : 0;
        int nuevo;
        if (actual > 0) {
            nuevo = actual + sentido;
            if (nuevo < 1) nuevo = 40;
            if (nuevo > 40) nuevo = 1;
        } else {
            // el canal más cercano por encima (subir) o por debajo (bajar)
            nuevo = sentido > 0 ? 1 : 40;
            long mejor = Long.MAX_VALUE;
            for (int k = 0; k < 40 && hz > 0; k++) {
                long d = (CANALES_CB_HZ[k] - hz) * sentido;
                if (d > 0 && d < mejor) { mejor = d; nuevo = k + 1; }
            }
        }
        irACanal(nuevo);
    }

    private JButton botonFlechaCanal(String texto, int sentido) {
        // Pequeñas y con los colores de la pantalla (fondo oscuro, flecha y borde en ámbar)
        JButton b = new JButton(texto);
        b.setFont(new Font("Arial", Font.BOLD, 9));
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setFocusable(false);
        b.setContentAreaFilled(false);
        b.setOpaque(true);
        b.setBackground(app.TEMA_CAMPO);
        b.setForeground(app.TEMA_AMBAR);
        b.setBorder(BorderFactory.createLineBorder(new Color(app.TEMA_AMBAR.getRed(), app.TEMA_AMBAR.getGreen(), app.TEMA_AMBAR.getBlue(), 160), 1, true));
        b.setPreferredSize(new Dimension(20, 16));
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setBackground(new Color(40, 55, 75)); }
            @Override public void mouseExited(MouseEvent e) { b.setBackground(app.TEMA_CAMPO); }
            @Override public void mousePressed(MouseEvent e) { b.setBackground(new Color(70, 60, 30)); }
            @Override public void mouseReleased(MouseEvent e) { b.setBackground(new Color(40, 55, 75)); }
        });
        b.setToolTipText(sentido > 0 ? "Canal siguiente" : "Canal anterior");
        b.addActionListener(e -> cambiarCanal(sentido));
        return b;
    }

    /** Recuadro "CANAL nn" de la pantalla, con las flechas ▲ ▼ una encima de otra. Solo se ve en CB. */
    private JPanel crearRecuadroCanal() {
        JPanel caja = new JPanel(new BorderLayout());
        caja.setOpaque(false);
        caja.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(app.TEMA_AMBAR, 1, true),
                BorderFactory.createEmptyBorder(2, 10, 2, 10)));
        JLabel tit = new JLabel("CANAL", SwingConstants.CENTER);
        tit.setFont(new Font("Arial", Font.BOLD, 10));
        tit.setForeground(app.TEMA_AMBAR);
        lblCanalLcd = new JLabel("--", SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics g) {
                // El número del canal también con cifras de 7 segmentos
                FontMetrics fm = getFontMetrics(getFont());
                int cw = fm.charWidth('0'), alto = fm.getAscent() - 4;
                String t = getText() == null ? "" : getText();
                if (t.length() == 1) t = " " + t;
                int x = (getWidth() - cw * t.length()) / 2, y = (getHeight() - alto) / 2;
                pintarSieteSegmentos((Graphics2D) g, t, x, y, cw, alto, getForeground());
            }
        };
        lblCanalLcd.setFont(new Font("Monospaced", Font.BOLD, 30));
        lblCanalLcd.setForeground(app.TEMA_AMBAR);
        caja.add(tit, BorderLayout.NORTH);
        caja.add(lblCanalLcd, BorderLayout.CENTER);
        // Tamaño fijo, con sitio para dos cifras: así no se encoge aunque la ventana sea estrecha
        FontMetrics fmCanal = lblCanalLcd.getFontMetrics(lblCanalLcd.getFont());
        Dimension tamCaja = new Dimension(Math.max(fmCanal.stringWidth("40"), tit.getPreferredSize().width) + 24,
                fmCanal.getHeight() + tit.getPreferredSize().height + 6);
        caja.setPreferredSize(tamCaja);
        caja.setMinimumSize(tamCaja);

        JPanel flechas = new JPanel(new GridLayout(2, 1, 0, 4));
        flechas.setOpaque(false);
        flechas.add(botonFlechaCanal("▲", +1));
        flechas.add(botonFlechaCanal("▼", -1));
        JPanel envolturaFlechas = new JPanel(new GridBagLayout());   // flechas centradas en vertical
        envolturaFlechas.setOpaque(false);
        envolturaFlechas.add(flechas);

        panelCanalLcd = new JPanel(new BorderLayout(12, 0));   // separación entre el canal y las flechas
        panelCanalLcd.setOpaque(false);
        panelCanalLcd.add(caja, BorderLayout.CENTER);
        panelCanalLcd.add(envolturaFlechas, BorderLayout.EAST);
        panelCanalLcd.setMinimumSize(panelCanalLcd.getPreferredSize());
        panelCanalLcd.setVisible(false);
        return panelCanalLcd;
    }

    /** Pestaña con los 40 canales (8 x 5). */
    private JPanel crearPanelCanales() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(new Color(15, 24, 38));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(app.TEMA_LINEA),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        JLabel tit = rotuloSeccion("40 CANALES  ·  pulsa uno para ir a esa frecuencia");
        JPanel rejilla = new JPanel(new GridLayout(5, 8, 3, 3));
        rejilla.setOpaque(false);
        for (int k = 0; k < 40; k++) {
            int canal = k + 1;
            JButton b = new JButton("<html><center><b style='font-size:11px'>" + canal + "</b><br>"
                    + "<span style='font-size:8px'>" + String.format(java.util.Locale.US, "%.3f", CANALES_CB_HZ[k] / 1e6)
                    + "</span></center></html>");
            b.setUI(new BotonEmisoraUI());   // mismo estilo que bandas y modos: texto blanco, naranja el canal en uso
            b.setForeground(Color.WHITE);
            b.setFocusable(false);
            b.setMargin(new Insets(2, 0, 2, 0));
            b.setToolTipText("Canal " + canal + ": " + String.format(java.util.Locale.US, "%.3f MHz", CANALES_CB_HZ[k] / 1e6)
                    + (canal == 9 ? " (emergencias)" : canal == 19 ? " (carretera)" : ""));
            if (canal == 9 || canal == 19) {
                b.setBorder(BorderFactory.createLineBorder(new Color(216, 160, 48), 2));
                b.setBorderPainted(true);
            }
            b.addActionListener(e -> irACanal(canal));
            botonesCanal[k] = b;
            rejilla.add(b);
        }
        JLabel nota = new JLabel("Borde dorado: canal 9 (emergencias) y 19 (carretera)");
        nota.setFont(new Font("Arial", Font.PLAIN, 10));
        nota.setForeground(new Color(143, 166, 200));
        p.add(tit, BorderLayout.NORTH);
        p.add(rejilla, BorderLayout.CENTER);
        p.add(nota, BorderLayout.SOUTH);
        return p;
    }

    /** Canal en pantalla, canal iluminado en la pestaña, y la pestaña que aparece/desaparece con la banda CB. */
    private void refrescarCanalesCB() {
        if (panelCanalLcd == null) return;
        boolean enCB = esBandaCB();
        long hz = herciosDeCampo(app.txtFreqTx);
        int canal = hz > 0 ? canalDeFrecuencia(hz) : 0;
        if (panelCanalLcd.isVisible() != enCB) panelCanalLcd.setVisible(enCB);
        String t = canal > 0 ? String.valueOf(canal) : "--";
        if (!t.equals(lblCanalLcd.getText())) lblCanalLcd.setText(t);
        for (int k = 0; k < 40; k++) {
            if (botonesCanal[k] == null) continue;
            Color c = (k + 1 == canal) ? VERDE_ENCENDIDO : Color.WHITE;
            if (!c.equals(botonesCanal[k].getForeground())) botonesCanal[k].setForeground(c);
        }
        if (tabCanales != null && enCB != estabaEnCB) {
            estabaEnCB = enCB;
            tabCanales.setVisible(enCB);
            if (enCB) {
                tabCanales.setSelected(true);
                cartasDerecha.show(contenidoDerecha, "CANALES");
            } else if (tabCanales.isSelected()) {
                tabMapa.setSelected(true);
                cartasDerecha.show(contenidoDerecha, "MAPA");
            }
        }
    }

    /** Inicio de cada banda (Región 1). */
    private static final Map<String, Integer> INICIO_BANDA_HZ = Map.ofEntries(
            Map.entry("160m", 1_810_000), Map.entry("80m", 3_500_000), Map.entry("60m", 5_351_500),
            Map.entry("40m", 7_000_000), Map.entry("30m", 10_100_000), Map.entry("20m", 14_000_000),
            Map.entry("17m", 18_068_000), Map.entry("15m", 21_000_000), Map.entry("12m", 24_890_000),
            Map.entry("10m", 28_000_000), Map.entry("6m", 50_000_000), Map.entry("2m", 144_000_000),
            Map.entry("70cm", 430_000_000), Map.entry("CB", 27_265_000));   // CB: canal 26 (en honor al radioclub CS26)

    private static final int PM_PARAM_FREQ = 0x2, PM_PARAM_FREQA = 0x4, PM_PARAM_FREQB = 0x8;

    /**
     * Cambia la frecuencia de la emisora. Algunas emisoras (como la TS-870 en OmniRig) no aceptan la frecuencia
     * "general", solo la del VFO A o B: entonces se escribe en el VFO que esté en uso.
     */
    static void ponerFrecuenciaEmisora(Dispatch rig, int hz) {
        boolean general = false;
        try { general = Dispatch.call(rig, "IsParamWriteable", new Variant(PM_PARAM_FREQ)).getBoolean(); } catch (Exception ignored) { }
        if (general) {
            Dispatch.put(rig, "Freq", new Variant(hz));
            return;
        }
        boolean enB = false;
        try {
            int v = Dispatch.get(rig, "Vfo").getInt();
            enB = v == PM_VFOB || v == PM_VFOBB || v == PM_VFOBA;
        } catch (Exception ignored) { }
        Dispatch.put(rig, enB ? "FreqB" : "FreqA", new Variant(hz));
    }

    /** Botón de banda: la pone en el contacto (inicio de banda y su modo) y, con CAT, en la emisora. */
    private void elegirBanda(String banda) {
        if (!comboContiene(app.cbBanda, banda)) return;
        app.cbBanda.setSelectedItem(banda);
        LibroGuardiaUI.InfoBanda info = app.CONFIG_BANDAS.get(banda);
        // Se va al INICIO de la banda, con el modo habitual de la banda
        Integer inicio = INICIO_BANDA_HZ.get(banda);
        if (info != null && inicio != null) {
            String f = String.format(java.util.Locale.GERMANY, "%,d", inicio);
            frecuenciaPorPrograma = true;
            try { app.txtFreqTx.setText(f); app.txtFreqRx.setText(f); } finally { frecuenciaPorPrograma = false; }
            app.cbModo.setSelectedItem(info.modo);
        }
        if (app.catConectado && info != null && inicio != null) {
            int hz = inicio;
            int modoBanda = codigoModoOmniRig(info.modo, hz);
            enviarOrden("banda " + banda, rig -> {
                ponerFrecuenciaEmisora(rig, hz);
                if (modoBanda != 0) Dispatch.put(rig, "Mode", new Variant(modoBanda));
            });
        }
        refrescarFrontal();
    }

    /** Botón de modo: lo pone en el contacto y, con CAT, en la emisora. */
    private void elegirModo(String modo) {
        if (!comboContiene(app.cbModo, modo)) return;
        app.cbModo.setSelectedItem(modo);
        if (app.catConectado) {
            long hz = herciosDeTexto(app.txtFreqTx.getText());
            int codigo = codigoModoOmniRig(modo, hz);
            if (codigo != 0) ordenPropiedad("modo " + modo, "Mode", codigo);
        }
        refrescarFrontal();
    }

    /** Código de modo de OmniRig. SSB = LSB por debajo de 10 MHz y USB por encima. */
    static int codigoModoOmniRig(String modo, long hz) {
        return switch (modo) {
            case "LSB" -> PM_SSB_L;
            case "USB" -> PM_SSB_U;
            case "SSB" -> (hz > 0 && hz < 10_000_000) ? PM_SSB_L : PM_SSB_U;
            case "CW" -> PM_CW_U;
            case "AM" -> PM_AM;
            case "FM" -> PM_FM;
            default -> 0;
        };
    }

    private static final int PM_PARAM_RITOFFSET = 0x20;

    volatile long ultimaFreqCatHz = 0;
    private volatile String consultaExtra = null;
    private volatile char vfoPedido891 = 0;

    /**
     * FT-891: la emisora no tiene orden para elegir VFO A o B ni dice cuál está en uso; su tecla A/B (orden SV)
     * intercambia los dos VFO. El programa recuerda cuál se ha pedido y solo intercambia cuando hace falta.
     */
    private void pedirVfoFt891(char vfo) {
        if (!app.catConectado) { enviarOrden("", rig -> { }); return; }
        boolean quiereB = vfo == 'B';
        if (quiereB != vfoB891) ordenDirecta("VFO " + vfo, "SV;");
        marcarVfo(quiereB);
        if (lblCtrlEstado != null) { lblCtrlEstado.setForeground(new Color(140, 230, 140)); lblCtrlEstado.setText("✓ VFO " + vfo); }
    }

    JButton btnVfoA, btnVfoB;
    private volatile boolean vfoB891 = false;

    /** Enciende en naranja el botón del VFO en uso. */
    void marcarVfo(boolean enB) {
        vfoB891 = enB;
        if (btnVfoA != null) btnVfoA.setSelected(!enB);
        if (btnVfoB != null) btnVfoB.setSelected(enB);
        if (btnVfoA != null) btnVfoA.repaint();
        if (btnVfoB != null) btnVfoB.repaint();
    }

    /** Respuesta "FAxxxxxxxxx" de la FT-891: si el VFO en uso no es el pedido, se cambia con SV. */
    private void respuestaFaFt891(String carga) {
        char pedido = vfoPedido891;
        if (pedido == 0) return;
        vfoPedido891 = 0;
        String d = carga.replaceAll("[^0-9]", "");
        if (d.isEmpty()) return;
        long fa = Long.parseLong(d);
        boolean enA = Math.abs(fa - ultimaFreqCatHz) < 5;   // la frecuencia en uso es la del VFO A
        if ((pedido == 'A' && !enA) || (pedido == 'B' && enA)) {
            SwingUtilities.invokeLater(() -> ordenDirecta("VFO " + pedido, "SV;"));
        }
    }

    boolean avisoSinEmisoraHecho = false;

    boolean esFt891EnUso() {
        EmisoraConfig e = emisoraActiva();
        return e != null && e.es891();
    }

    /** FT-891: el RIT y el XIT son el CLARIFIER (orden CF0 + RX encendido + TX encendido). */
    private void ordenClarFt891(String descripcion) {
        boolean rit = tgRit != null && tgRit.isSelected(), xit = tgXit != null && tgXit.isSelected();
        ordenDirecta(descripcion, "CF0" + (rit ? 1 : 0) + (xit ? 1 : 0) + ";");
    }

    /**
     * Pone el desplazamiento del RIT/XIT. Si OmniRig sabe ponerlo directamente, se usa eso.
     * Si no (como en la TS-870), se sube o baja a pasos con las órdenes RU; / RD; de Kenwood,
     * comprobando después lo que marca la emisora y corrigiendo hasta llegar al valor pedido.
     */
    private void ajustarRitOffset(Dispatch rig, int objetivo) {
        boolean directo = false;
        try { directo = Dispatch.call(rig, "IsParamWriteable", new Variant(PM_PARAM_RITOFFSET)).getBoolean(); } catch (Exception ignored) { }
        if (directo) {
            Dispatch.put(rig, "RitOffset", new Variant(objetivo));
            return;
        }
        String tipo = "";
        try { tipo = Dispatch.get(rig, "RigType").getString().toUpperCase(); } catch (Exception ignored) { }
        if (!tipo.startsWith("TS")) {
            throw new IllegalStateException("esta emisora no deja cambiar el desplazamiento desde el ordenador");
        }
        for (int intento = 0; intento < 5; intento++) {
            int actual = Dispatch.get(rig, "RitOffset").getInt();
            int diferencia = objetivo - actual;
            if (Math.abs(diferencia) < 10) return;
            int pasos = Math.min(250, Math.max(1, Math.round(Math.abs(diferencia) / 10f)));
            String orden = diferencia > 0 ? "RU;" : "RD;";
            // Se mandan en grupos para no saturar el puerto
            while (pasos > 0) {
                int grupo = Math.min(25, pasos);
                Dispatch.call(rig, "SendCustomCommand", new Variant(orden.repeat(grupo)), new Variant(0), new Variant(""));
                pasos -= grupo;
            }
            try {
                Thread.sleep(700);   // tiempo para que OmniRig vuelva a leer la emisora
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Lee la frecuencia escrita: 14.205.000 (Hz), 14205 o 14.205 (kHz), 14,205 (MHz). Devuelve -1 si no vale. */
    static long herciosDeTexto(String texto) {
        String t = texto == null ? "" : texto.trim();
        // Solo cifras (con o sin puntos de miles): se decide por cuántas cifras hay, como en un display
        //   1-3 cifras = MHz (7, 14, 145)   4-6 cifras = kHz (7100, 14205, 145500)   7-9 cifras = Hz (7100000)
        // (Windows sin datos de idioma escribe los miles con coma: 27,125,000 vale igual que 27.125.000)
        if (t.matches("\\d+") || t.matches("\\d{1,3}(\\.\\d{3})+") || t.matches("\\d{1,3}(,\\d{3}){2,}")) {
            String d = t.replace(".", "").replace(",", "");
            long v = Long.parseLong(d);
            long hz = d.length() <= 3 ? v * 1_000_000 : d.length() <= 6 ? v * 1000 : v;
            return (hz < 100_000 || hz > 470_000_000) ? -1 : hz;
        }
        return textoAHercios(t);
    }

    /** Con CAT: manda a la emisora la frecuencia escrita en la pantalla. */
    private void qsyDesdeCampo() {
        long hz = herciosDeTexto(app.txtFreqTx.getText());
        if (hz < 0) {
            JOptionPane.showMessageDialog(app, "Escribe la frecuencia, por ejemplo 14.205.000, 14205 (kHz) o 14,205 (MHz).",
                    "Frecuencia", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final int hzInt = (int) hz;
        app.txtFreqTx.setText(String.format(java.util.Locale.GERMANY, "%,d", hz));
        app.txtFreqRx.setText(app.txtFreqTx.getText());
        enviarOrden(String.format(java.util.Locale.GERMANY, "QSY %,.3f kHz", hz / 1000.0),
                rig -> ponerFrecuenciaEmisora(rig, hzInt));
        app.txtIndicativo.requestFocusInWindow();   // deja de escribir en la frecuencia: la emisora vuelve a mandar
    }

    // ===================== FIN CONTROL DE LA EMISORA =====================
}
