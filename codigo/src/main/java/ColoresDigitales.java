import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Colores de la ventana DIGITALES, como los de WSJT-X (Ajustes → Colores): cada tipo de mensaje con su casilla
 * para activarlo, su color de fondo y su color de letra (o «predeterminado»). El orden de la lista es la prioridad:
 * si un mensaje cumple varias, el fondo lo pone la de más arriba que tenga fondo y la letra la de más arriba que
 * tenga letra (como en WSJT-X). Se guarda en la configuración (DIGITALES_COLOR_...).
 */
final class ColoresDigitales {

    /** Un tipo de mensaje que se puede resaltar. fondo / letra = null: «predeterminado». */
    static final class Tipo {
        final String clave, nombre;
        boolean activo;
        Color fondo, letra;
        Tipo(String clave, String nombre) { this.clave = clave; this.nombre = nombre; }
        Tipo copia() { Tipo t = new Tipo(clave, nombre); t.activo = activo; t.fondo = fondo; t.letra = letra; return t; }
    }

    /** Cómo se pinta una línea. normal = no le toca ningún color. */
    record Estilo(Color fondo, Color letra, boolean normal) { }

    static final String MIO = "MIO", CONT = "CONT", CONT_BANDA = "CONT_BANDA", CQZ = "CQZ", CQZ_BANDA = "CQZ_BANDA",
            ITU = "ITU", ITU_BANDA = "ITU_BANDA", DXCC = "DXCC", DXCC_BANDA = "DXCC_BANDA", GRID = "GRID", GRID_BANDA = "GRID_BANDA",
            CALL = "CALL", CALL_BANDA = "CALL_BANDA", LOTW = "LOTW", CQ = "CQ", TX = "TX";

    private static final String[][] NOMBRES = {
            {MIO, "Mi indicativo en el mensaje"}, {CONT, "Nuevo continente"}, {CONT_BANDA, "Nuevo continente en banda"},
            {CQZ, "Nueva zona CQ"}, {CQZ_BANDA, "Nueva zona CQ en banda"}, {ITU, "Nueva zona ITU"}, {ITU_BANDA, "Nueva zona ITU en banda"},
            {DXCC, "Nuevo DXCC"}, {DXCC_BANDA, "Nuevo DXCC en banda"}, {GRID, "Nuevo locator"}, {GRID_BANDA, "Nuevo locator en banda"},
            {CALL, "Nuevo indicativo"}, {CALL_BANDA, "Nuevo indicativo en banda"}, {LOTW, "Usuario LoTW"},
            {CQ, "CQ en el mensaje"}, {TX, "Mensaje transmitido"}};

    /** Colores de fábrica 1 de WSJT-X: {clave, activo, fondo, letra} (null = predeterminado). En este orden. */
    private static final Object[][] FABRICA_1 = {
            {MIO, true, 0xFF6666, null}, {CONT, true, 0xFF0063, null}, {CONT_BANDA, true, 0xFF99C2, null},
            {CQZ, true, 0xFFBF00, null}, {CQZ_BANDA, true, 0xFFE499, null}, {ITU, false, 0xA6FF00, null}, {ITU_BANDA, false, 0xDDFF99, null},
            {DXCC, true, 0xFF00FF, null}, {DXCC_BANDA, true, 0xFFAAFF, null}, {GRID, false, 0xFF8000, null}, {GRID_BANDA, false, 0xFFCC99, null},
            {CALL, false, 0x00FFFF, null}, {CALL_BANDA, false, 0x99FFFF, null}, {LOTW, false, null, 0x990000},
            {CQ, true, 0x66FF66, null}, {TX, true, 0xFFFF00, null}};

    /** Colores de fábrica 2 de WSJT-X (otro orden de prioridad y letras en rojo oscuro). */
    private static final Object[][] FABRICA_2 = {
            {LOTW, true, null, 0x000000}, {MIO, true, 0x00FFFF, 0xAA0000}, {CONT, true, 0xFF50FF, 0xAA0000}, {CONT_BANDA, true, 0xFF82FF, 0xAA0000},
            {DXCC, true, 0xFFFF00, 0xAA0000}, {DXCC_BANDA, true, 0xFFFF9B, 0xAA0000}, {CQZ, true, 0xAAAA00, 0xAA0000}, {CQZ_BANDA, true, 0xAAAA7F, 0xAA0000},
            {ITU, true, 0x37BDFF, 0xAA0000}, {ITU_BANDA, true, 0x91DEFF, 0xAA0000}, {GRID, true, 0xFFA880, 0xAA0000}, {GRID_BANDA, true, 0xFFCD9B, 0xAA0000},
            {CALL, true, 0x64FF64, 0xAA0000}, {CALL_BANDA, true, 0xB4FFB4, 0xAA0000}, {CQ, true, 0xC3C3C3, 0xAA0000}, {TX, true, 0xFFA5C6, 0x000000}};

    /** Lo que no tiene ningún color (el fondo de la ventana). */
    static final Color NORMAL_FONDO = new Color(0x1C2A3E), NORMAL_LETRA = new Color(0xDDE5EE);
    static final Color NARANJA = new Color(0xFFA500), AZUL = new Color(0x5AA0FF);

    /** La lista, en orden de prioridad. */
    static final List<Tipo> LISTA = new ArrayList<>();

    // Opciones (como las de WSJT-X)
    static boolean porModo, soloCampos, wae, tambien73, naranjaActivo, azulActivo;
    static String naranjaLista = "", azulLista = "", lotwUrl = DatosDigitales.URL_LOTW;
    static int lotwDias = 365;
    private static Set<String> naranjaSet = Set.of(), azulSet = Set.of();

    static { cargar(); }

    private static String nombreDe(String clave) {
        for (String[] n : NOMBRES) if (n[0].equals(clave)) return n[1];
        return clave;
    }

    private static List<Tipo> fabrica(Object[][] f) {
        List<Tipo> l = new ArrayList<>();
        for (Object[] x : f) {
            Tipo t = new Tipo((String) x[0], nombreDe((String) x[0]));
            t.activo = (Boolean) x[1];
            t.fondo = x[2] == null ? null : new Color((Integer) x[2]);
            t.letra = x[3] == null ? null : new Color((Integer) x[3]);
            l.add(t);
        }
        return l;
    }

    static void cargar() {
        LISTA.clear();
        List<Tipo> fab = fabrica(FABRICA_1);
        String orden = LibroGuardiaUI.ConfigApp.get("DIGITALES_COLORES_ORDEN", "");
        List<String> claves = new ArrayList<>();
        for (String c : orden.split(",")) if (!c.isBlank()) claves.add(c.trim());
        for (Tipo t : fab) if (!claves.contains(t.clave)) claves.add(fab.indexOf(t) <= claves.size() ? fab.indexOf(t) : claves.size(), t.clave);
        for (String c : claves) {
            Tipo f = null;
            for (Tipo t : fab) if (t.clave.equals(c)) f = t;
            if (f == null || LISTA.stream().anyMatch(x -> x.clave.equals(c))) continue;
            String k = "DIGITALES_COLOR_" + c;
            f.activo = LibroGuardiaUI.ConfigApp.getInt(k + "_ACTIVO", f.activo ? 1 : 0) == 1;
            f.fondo = leer(k + "_FONDO", f.fondo);
            f.letra = leer(k + "_LETRA", f.letra);
            LISTA.add(f);
        }
        porModo = LibroGuardiaUI.ConfigApp.getInt("DIGITALES_COLOR_POR_MODO", 0) == 1;
        soloCampos = LibroGuardiaUI.ConfigApp.getInt("DIGITALES_COLOR_SOLO_CAMPOS", 0) == 1;
        wae = LibroGuardiaUI.ConfigApp.getInt("DIGITALES_COLOR_WAE", 0) == 1;
        tambien73 = LibroGuardiaUI.ConfigApp.getInt("DIGITALES_COLOR_73", 1) == 1;
        naranjaActivo = LibroGuardiaUI.ConfigApp.getInt("DIGITALES_COLOR_NARANJA", 0) == 1;
        azulActivo = LibroGuardiaUI.ConfigApp.getInt("DIGITALES_COLOR_AZUL", 0) == 1;
        naranjaLista = LibroGuardiaUI.ConfigApp.get("DIGITALES_COLOR_NARANJA_LISTA", "");
        azulLista = LibroGuardiaUI.ConfigApp.get("DIGITALES_COLOR_AZUL_LISTA", "");
        lotwUrl = LibroGuardiaUI.ConfigApp.get("DIGITALES_LOTW_URL", DatosDigitales.URL_LOTW);
        lotwDias = Math.max(1, LibroGuardiaUI.ConfigApp.getInt("DIGITALES_LOTW_DIAS", 365));
        naranjaSet = lista(naranjaLista);
        azulSet = lista(azulLista);
    }

    private static Set<String> lista(String s) {
        Set<String> r = new LinkedHashSet<>();
        for (String x : s.toUpperCase(Locale.ROOT).split("[\\s,;]+")) if (!x.isBlank()) r.add(x.trim());
        return r;
    }

    /** Color guardado: "-" = predeterminado; sin guardar = el de fábrica. */
    private static Color leer(String clave, Color fabrica) {
        try {
            String v = LibroGuardiaUI.ConfigApp.get(clave, "");
            if (v.isEmpty()) return fabrica;
            if (v.equals("-")) return null;
            return new Color(Integer.parseInt(v, 16));
        } catch (Exception e) {
            return fabrica;
        }
    }

    private static String texto(Color c) { return c == null ? "-" : String.format("%06X", c.getRGB() & 0xFFFFFF); }

    private static void guardar() {
        StringBuilder orden = new StringBuilder();
        for (Tipo t : LISTA) {
            if (orden.length() > 0) orden.append(',');
            orden.append(t.clave);
            String k = "DIGITALES_COLOR_" + t.clave;
            LibroGuardiaUI.ConfigApp.putInt(k + "_ACTIVO", t.activo ? 1 : 0);
            LibroGuardiaUI.ConfigApp.put(k + "_FONDO", texto(t.fondo));
            LibroGuardiaUI.ConfigApp.put(k + "_LETRA", texto(t.letra));
        }
        LibroGuardiaUI.ConfigApp.put("DIGITALES_COLORES_ORDEN", orden.toString());
        LibroGuardiaUI.ConfigApp.putInt("DIGITALES_COLOR_POR_MODO", porModo ? 1 : 0);
        LibroGuardiaUI.ConfigApp.putInt("DIGITALES_COLOR_SOLO_CAMPOS", soloCampos ? 1 : 0);
        LibroGuardiaUI.ConfigApp.putInt("DIGITALES_COLOR_WAE", wae ? 1 : 0);
        LibroGuardiaUI.ConfigApp.putInt("DIGITALES_COLOR_73", tambien73 ? 1 : 0);
        LibroGuardiaUI.ConfigApp.putInt("DIGITALES_COLOR_NARANJA", naranjaActivo ? 1 : 0);
        LibroGuardiaUI.ConfigApp.putInt("DIGITALES_COLOR_AZUL", azulActivo ? 1 : 0);
        LibroGuardiaUI.ConfigApp.put("DIGITALES_COLOR_NARANJA_LISTA", naranjaLista);
        LibroGuardiaUI.ConfigApp.put("DIGITALES_COLOR_AZUL_LISTA", azulLista);
        LibroGuardiaUI.ConfigApp.put("DIGITALES_LOTW_URL", lotwUrl);
        LibroGuardiaUI.ConfigApp.putInt("DIGITALES_LOTW_DIAS", lotwDias);
        naranjaSet = lista(naranjaLista);
        azulSet = lista(azulLista);
    }

    /**
     * Cómo se pinta un mensaje. «cumple» son las claves que le corresponden (MIO, DXCC, CQ...). Como en WSJT-X:
     * se recorre la lista por orden y el fondo lo pone la primera activa que tenga fondo y la letra la primera
     * activa que tenga letra. Los indicativos de las listas naranja y azul mandan sobre todo.
     */
    static Estilo estilo(Set<String> cumple, String quien) {
        Color fondo = null, letra = null;
        if (quien != null && !quien.isEmpty()) {
            if (naranjaActivo && naranjaSet.contains(quien)) fondo = NARANJA;
            else if (azulActivo && azulSet.contains(quien)) fondo = AZUL;
        }
        for (Tipo t : LISTA) {
            if (!t.activo || !cumple.contains(t.clave)) continue;
            if (fondo == null && t.fondo != null) fondo = t.fondo;
            if (letra == null && t.letra != null) letra = t.letra;
        }
        if (fondo == null && letra == null) return new Estilo(NORMAL_FONDO, NORMAL_LETRA, true);
        if (fondo == null) return new Estilo(NORMAL_FONDO, legible(letra), false);
        return new Estilo(fondo, letra == null ? Color.BLACK : letra, false);
    }

    /** Sobre el fondo oscuro de la ventana, una letra muy oscura (p. ej. la de LoTW) se aclara para que se lea. */
    private static Color legible(Color c) {
        double l = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
        if (l >= 110) return c;
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        return Color.getHSBColor(hsb[0], hsb[1] * 0.75f, 1f);
    }

    /** ¿Este tipo de color es «nuevo ...» (solo se aplica a CQ, y a 73/RR73 si se marca la opción)? */
    static boolean esNuevo(String clave) {
        return !clave.equals(MIO) && !clave.equals(CQ) && !clave.equals(TX) && !clave.equals(LOTW);
    }

    // ================================================================== el apartado COLORES de Ajustes
    /** El apartado de Ajustes: se construye con una copia y solo se guarda al pulsar Aceptar (aplicar). */
    static final class Apartado {
        final JComponent componente;
        private final List<Tipo> copia = new ArrayList<>();
        private final JPanel filas = new JPanel();
        private final JCheckBox cModo, cCampos, cWae, c73, cNaranja, cAzul;
        private final JTextField tNaranja, tAzul, tLotw;
        private final JSpinner sDias;
        private final Window dueno;

        Apartado(Window dueno, Runnable releerLog) {
            this.dueno = dueno;
            for (Tipo t : LISTA) copia.add(t.copia());
            JPanel p = new AlAncho(new GridBagLayout());
            p.setOpaque(false);
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = 0; g.gridwidth = 3; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.insets = new Insets(2, 2, 2, 2);
            p.add(nota("Pincha en una línea para cambiar su color de fondo o de letras, o para subirla o bajarla "
                    + "(la de más arriba manda). Los colores «nuevo...» solo se ponen en los CQ (y en los 73 si marcas esa opción)."), g);
            filas.setLayout(new GridLayout(0, 1, 0, 0));
            filas.setBorder(BorderFactory.createLineBorder(Color.GRAY));
            pintarFilas();
            g.gridy++; p.add(filas, g);
            JButton f1 = boton("Colores 1"), f2 = boton("Colores 2"), rl = boton("Volver a leer el LOG");
            f1.setToolTipText("Pone todos los colores y su orden como vienen de fábrica (juego 1)");
            f1.addActionListener(e -> fabrica(FABRICA_1, "1"));
            f2.setToolTipText("Pone todos los colores y su orden como el juego 2 (letras en rojo oscuro)");
            f2.addActionListener(e -> fabrica(FABRICA_2, "2"));
            rl.setToolTipText("Vuelve a mirar en tu LOG lo que ya has trabajado (países, zonas, locators, indicativos)");
            rl.addActionListener(e -> { if (releerLog != null) releerLog.run(); });
            g.gridwidth = 1; g.gridy++;
            g.gridx = 0; p.add(f1, g); g.gridx = 1; p.add(f2, g); g.gridx = 2; p.add(rl, g);
            cModo = casilla("Destacar por modo", porModo, "Lo trabajado cuenta solo si fue en el mismo modo (FT8 o FT4)");
            cCampos = casilla("Solo campos de locator buscados", soloCampos, "Para «Nuevo locator» cuentan solo las 2 primeras letras (el campo, p. ej. IN)");
            cWae = casilla("Incluir entidades WAE adicionales", wae, "Cuenta como país aparte las entidades WAE (Sicilia, Shetland...) del cty.dat");
            c73 = casilla("Resaltar también los mensajes con 73 o RR73", tambien73, "Los colores «nuevo...» también en los mensajes que terminan con 73 o RR73");
            cNaranja = casilla("Resaltar en naranja:", naranjaActivo, "Estos indicativos (separados por espacios o comas) salen con fondo naranja");
            cAzul = casilla("Resaltar en azul:", azulActivo, "Estos indicativos (separados por espacios o comas) salen con fondo azul");
            tNaranja = campo(naranjaLista);
            tAzul = campo(azulLista);
            JPanel op = new JPanel(new GridBagLayout());
            op.setOpaque(false);
            GridBagConstraints o = new GridBagConstraints();
            o.insets = new Insets(1, 2, 1, 2); o.anchor = GridBagConstraints.WEST; o.fill = GridBagConstraints.HORIZONTAL;
            o.gridx = 0; o.gridy = 0; op.add(cModo, o); o.gridx = 1; op.add(cNaranja, o);
            o.gridx = 0; o.gridy = 1; op.add(cCampos, o); o.gridx = 1; o.weightx = 1; op.add(tNaranja, o); o.weightx = 0;
            o.gridx = 0; o.gridy = 2; op.add(cWae, o); o.gridx = 1; op.add(cAzul, o);
            o.gridx = 0; o.gridy = 3; op.add(c73, o); o.gridx = 1; o.weightx = 1; op.add(tAzul, o);
            g.gridx = 0; g.gridwidth = 3; g.gridy++; p.add(op, g);
            // LoTW
            tLotw = campo(lotwUrl);
            sDias = new JSpinner(new SpinnerNumberModel(lotwDias, 1, 3650, 1));
            JLabel eLotw = etiqueta(DatosDigitales.estadoLotw());
            JButton bLotw = boton("Buscar ahora");
            bLotw.addActionListener(e -> descargar(bLotw, eLotw, () -> tLotw.getText().trim(), DatosDigitales::ficheroLotw,
                    () -> { DatosDigitales.olvidarLotw(); return DatosDigitales.estadoLotw(); }));
            JPanel lw = new JPanel(new GridBagLayout());
            lw.setOpaque(false);
            GridBagConstraints q = new GridBagConstraints();
            q.insets = new Insets(1, 2, 1, 2); q.anchor = GridBagConstraints.WEST; q.fill = GridBagConstraints.HORIZONTAL;
            q.gridx = 0; q.gridy = 0; lw.add(etiqueta("Lista de usuarios de LoTW:"), q);
            q.gridx = 1; q.weightx = 1; lw.add(tLotw, q); q.weightx = 0; q.gridx = 2; lw.add(bLotw, q);
            q.gridx = 0; q.gridy = 1; lw.add(etiqueta("Última subida a LoTW hace menos de:"), q);
            JPanel dd = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            dd.setOpaque(false);
            dd.add(sDias); dd.add(etiqueta("días"));
            q.gridx = 1; lw.add(dd, q);
            q.gridx = 0; q.gridy = 2; q.gridwidth = 3; lw.add(eLotw, q);
            g.gridy++; p.add(titulado("Usuarios de LoTW", lw), g);
            // cty.dat y CALL3
            JLabel eCty = etiqueta("Versión del archivo cty.dat: " + DatosDigitales.versionCty());
            JButton bCty = boton("Descargar el último cty.dat");
            bCty.setToolTipText("Países, continentes y zonas CQ / ITU de cada indicativo (de country-files.com)");
            bCty.addActionListener(e -> descargar(bCty, eCty, () -> DatosDigitales.URL_CTY, DatosDigitales::ficheroCty,
                    () -> { DatosDigitales.olvidarCty(); if (releerLog != null) releerLog.run(); return "Versión del archivo cty.dat: " + DatosDigitales.versionCty(); }));
            JLabel eC3 = etiqueta("Archivo CALL3: " + DatosDigitales.estadoCall3());
            JButton bC3 = boton("Descargar CALL3.TXT (uso terrestre)"), bC3e = boton("Descargar CALL3.TXT (EME)");
            bC3.setToolTipText("Locators de muchos indicativos: se usa cuando el mensaje no trae el locator");
            bC3e.setToolTipText(bC3.getToolTipText());
            bC3.addActionListener(e -> descargar(bC3, eC3, () -> DatosDigitales.URL_CALL3, DatosDigitales::ficheroCall3,
                    () -> { DatosDigitales.olvidarCall3(); return "Archivo CALL3: " + DatosDigitales.estadoCall3(); }));
            bC3e.addActionListener(e -> descargar(bC3e, eC3, () -> DatosDigitales.URL_CALL3_EME, DatosDigitales::ficheroCall3,
                    () -> { DatosDigitales.olvidarCall3(); return "Archivo CALL3: " + DatosDigitales.estadoCall3(); }));
            JPanel ar = new JPanel(new GridBagLayout());
            ar.setOpaque(false);
            GridBagConstraints a = new GridBagConstraints();
            a.insets = new Insets(1, 2, 1, 2); a.anchor = GridBagConstraints.WEST; a.fill = GridBagConstraints.HORIZONTAL;
            a.gridx = 0; a.gridy = 0; a.weightx = 1; ar.add(eCty, a); a.weightx = 0; a.gridx = 1; a.gridwidth = 2; ar.add(bCty, a);
            a.gridwidth = 1; a.gridx = 0; a.gridy = 1; a.weightx = 1; ar.add(eC3, a); a.weightx = 0; a.gridx = 1; ar.add(bC3, a); a.gridx = 2; ar.add(bC3e, a);
            g.gridy++; p.add(titulado("Archivos (cty.dat y CALL3)", ar), g);
            g.gridy++; g.weighty = 1; JPanel relleno = new JPanel(); relleno.setOpaque(false); p.add(relleno, g);
            JScrollPane sc = new JScrollPane(p, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            sc.setBorder(null);
            sc.setOpaque(false);
            sc.getViewport().setOpaque(false);
            sc.getVerticalScrollBar().setUnitIncrement(16);
            componente = sc;
        }

        /** Guarda lo elegido (al pulsar Aceptar en Ajustes). */
        void aplicar() {
            LISTA.clear();
            for (Tipo t : copia) LISTA.add(t.copia());
            porModo = cModo.isSelected(); soloCampos = cCampos.isSelected(); wae = cWae.isSelected(); tambien73 = c73.isSelected();
            naranjaActivo = cNaranja.isSelected(); azulActivo = cAzul.isSelected();
            naranjaLista = tNaranja.getText().trim().toUpperCase(Locale.ROOT);
            azulLista = tAzul.getText().trim().toUpperCase(Locale.ROOT);
            String u = tLotw.getText().trim();
            lotwUrl = u.isEmpty() ? DatosDigitales.URL_LOTW : u;
            lotwDias = (Integer) sDias.getValue();
            guardar();
        }

        private void fabrica(Object[][] f, String n) {
            int r = JOptionPane.showConfirmDialog(dueno, "¿Poner todos los colores y su orden como en «Colores " + n + "»?",
                    "Colores", JOptionPane.YES_NO_OPTION);
            if (r != JOptionPane.YES_OPTION) return;
            copia.clear();
            copia.addAll(ColoresDigitales.fabrica(f));
            pintarFilas();
        }

        private void pintarFilas() {
            filas.removeAll();
            for (Tipo t : copia) {
                JPanel fila = new JPanel(new BorderLayout(4, 0));
                fila.setOpaque(true);
                JCheckBox chk = new JCheckBox();
                chk.setOpaque(false);
                chk.setSelected(t.activo);
                chk.addActionListener(e -> t.activo = chk.isSelected());
                JLabel l = new JLabel();
                l.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
                Runnable pintar = () -> {
                    fila.setBackground(t.fondo != null ? t.fondo : Color.WHITE);
                    l.setForeground(t.letra != null ? t.letra : Color.BLACK);
                    l.setText(t.nombre + (t.letra == null ? " [Color letras predeterminado]" : "")
                            + (t.fondo == null ? " [Color fondo predeterminado]" : ""));
                };
                pintar.run();
                fila.add(chk, BorderLayout.WEST);
                fila.add(l);
                fila.setToolTipText("Pincha para cambiar los colores o el orden");
                MouseAdapter ma = new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) { menu(t, fila, e.getX(), e.getY(), pintar); }
                };
                l.addMouseListener(ma);
                fila.addMouseListener(ma);
                filas.add(fila);
            }
            filas.revalidate();
            filas.repaint();
        }

        private void menu(Tipo t, JComponent sobre, int x, int y, Runnable pintar) {
            JPopupMenu m = new JPopupMenu();
            JMenuItem fondo = new JMenuItem("Color de fondo…"), letra = new JMenuItem("Color de letras…"),
                    sinFondo = new JMenuItem("Quitar el color de fondo (predeterminado)"), sinLetra = new JMenuItem("Quitar el color de letras (predeterminado)"),
                    subir = new JMenuItem("Subir (más prioridad)"), bajar = new JMenuItem("Bajar (menos prioridad)");
            fondo.addActionListener(e -> {
                Color c = JColorChooser.showDialog(dueno, "Color de fondo: " + t.nombre, t.fondo == null ? Color.WHITE : t.fondo);
                if (c != null) { t.fondo = c; pintar.run(); }
            });
            letra.addActionListener(e -> {
                Color c = JColorChooser.showDialog(dueno, "Color de letras: " + t.nombre, t.letra == null ? Color.BLACK : t.letra);
                if (c != null) { t.letra = c; pintar.run(); }
            });
            sinFondo.addActionListener(e -> { t.fondo = null; pintar.run(); });
            sinLetra.addActionListener(e -> { t.letra = null; pintar.run(); });
            int i = copia.indexOf(t);
            subir.setEnabled(i > 0);
            bajar.setEnabled(i < copia.size() - 1);
            subir.addActionListener(e -> { copia.remove(i); copia.add(i - 1, t); pintarFilas(); });
            bajar.addActionListener(e -> { copia.remove(i); copia.add(i + 1, t); pintarFilas(); });
            sinFondo.setEnabled(t.fondo != null);
            sinLetra.setEnabled(t.letra != null);
            m.add(fondo); m.add(letra); m.add(sinFondo); m.add(sinLetra); m.addSeparator(); m.add(subir); m.add(bajar);
            m.show(sobre, x, y);
        }

        private void descargar(JButton b, JLabel estado, java.util.function.Supplier<String> url, java.util.function.Supplier<java.io.File> destino,
                               java.util.function.Supplier<String> despues) {
            b.setEnabled(false);
            String antes = estado.getText();
            estado.setText("Descargando…");
            Thread t = new Thread(() -> {
                String res;
                try {
                    DatosDigitales.descargar(url.get(), destino.get());
                    res = despues.get();
                } catch (Exception ex) {
                    res = "No se ha podido descargar: " + ex.getMessage();
                }
                String fin = res;
                SwingUtilities.invokeLater(() -> { estado.setText(fin); b.setEnabled(true); if (fin.startsWith("No se")) estado.setToolTipText(antes); });
            }, "DescargaDigitales");
            t.setDaemon(true);
            t.start();
        }
    }

    // ------------------------------------------------------------------ piezas (con el aspecto de DIGITALES)
    private static JButton boton(String t) { return VentanaDigitales.boton(t); }

    private static JCheckBox casilla(String t, boolean sel, String ayuda) {
        JCheckBox c = new JCheckBox(t, sel);
        c.setOpaque(false);
        c.setFont(VentanaDigitales.f(Font.PLAIN, 13));
        c.setForeground(VentanaDigitales.TEXTO);
        c.setToolTipText(ayuda);
        return c;
    }

    private static JTextField campo(String t) {
        JTextField f = new JTextField(t, 12);
        f.setFont(VentanaDigitales.f(Font.PLAIN, 13));
        return f;
    }

    private static JLabel etiqueta(String t) { return VentanaDigitales.lbl(t, VentanaDigitales.f(Font.PLAIN, 13), VentanaDigitales.TEXTO); }

    private static JLabel nota(String t) {
        return VentanaDigitales.lbl("<html><div style='width:560px'><span style='color:#8fa6c8'>" + t + "</span></div></html>", VentanaDigitales.f(Font.PLAIN, 12), VentanaDigitales.TEXTO);
    }

    /** Panel que se ajusta al ancho de la ventana (sin barra horizontal); si no cabe de alto, sale la barra vertical. */
    private static final class AlAncho extends JPanel implements Scrollable {
        AlAncho(LayoutManager l) { super(l); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() > getPreferredSize().height; }
    }

    private static JPanel titulado(String titulo, JComponent dentro) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        javax.swing.border.TitledBorder tb = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(VentanaDigitales.LINEA), " " + titulo + " ");
        tb.setTitleFont(VentanaDigitales.f(Font.BOLD, 12));
        tb.setTitleColor(VentanaDigitales.TEXTO);
        p.setBorder(BorderFactory.createCompoundBorder(tb, BorderFactory.createEmptyBorder(2, 4, 4, 4)));
        p.add(dentro);
        return p;
    }
}
